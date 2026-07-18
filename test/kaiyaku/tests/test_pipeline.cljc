(ns kaiyaku.tests.test-pipeline
  "kaiyaku 解約 — R1 END-TO-END pipeline test (ADR-2606112201 R1).

  One integration that threads analyze→plan→enrich→dispatch→serviceop→receipt over a
  minimal in-memory ledger, proving the seven R1 pieces COMPOSE (and catches interface
  drift any single change would introduce). Uses a 2-tie graph:
    - 'netflix'           (cancel api :none / browser :prohibited) → T3 → member-submits, no op
    - 'generic-saas-api'  (cancel api :available)                  → T1 → karakuri ServiceOp
  both real catalog ids (so enrichment fires), both member-approved in the capability."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [kaiyaku.methods.pipeline :as pipeline]
            [kaiyaku.methods.catalog :as catalog]
            [kaiyaku.methods.karakuri-bridge :as kb]
            [kaiyaku.methods.kotoba :as k]))

(def actor-dir (io/file "."))
(defn- catalog-by-id []
  (catalog/by-id (catalog/load-file* (io/file actor-dir "data" "cancel-procedures.kotoba.edn"))))

;; minimal string-key ledger graph (the analyze/load-file* parsed shape)
(def member "member:test")
(defn- node [id api browser]
  {":svc/id" id ":svc/label" id ":svc/kind" ":subscription" ":svc/category" "x"
   ":svc/cancel" {":api" api ":browser" browser ":self-submit" true}
   ":svc/notice-days" 0 ":svc/penalty-jpy" 0})
(def nodes
  {member {":member/id" member ":member/label" "Test"}
   "netflix" (node "netflix" ":none" ":prohibited")
   "generic-saas-api" (node "generic-saas-api" ":available" ":prohibited")})
(defn- edge [to]
  {":en/from" member ":en/to" to ":en/kind" ":subscribes"
   ":en/monthly-cost-jpy" 1500.0 ":en/usage-score" 5.0 ":en/last-used-days" 10.0})
(def edges [(edge "netflix") (edge "generic-saas-api")])

(def bundle
  {"cacao_b64" "opaque" "aud" "did:web:etzhayyim.com" "capability" "service:cancel"
   "graph" "graph:kaiyaku" "exp" 9999999999 "nonce" "n"
   "approved" ["netflix" "generic-saas-api"]})

(defn- run [] (pipeline/run {:nodes nodes :edges edges :catalog (catalog-by-id)
                             :bundle bundle :now-epoch 1000 :as-of "T0"}))

(deftest test-pipeline-plans-and-tiers
  (let [{:keys [plans]} (run)]
    (is (= 2 (count plans)))
    (is (= #{"netflix" "generic-saas-api"} (set (map #(get % "svc") plans))))
    (let [by-svc (into {} (map (juxt #(get % "svc") #(get % "tier")) plans))]
      (is (= "T3" (by-svc "netflix")))
      (is (= "T1" (by-svc "generic-saas-api"))))))

(deftest test-pipeline-enrichment-fires
  (let [{:keys [enriched]} (run)]
    (is (every? #(true? (get % "catalog_coverage")) enriched))
    (is (every? #(seq (get-in % ["catalog" "self_submit_steps"])) enriched))))

(deftest test-pipeline-all-authorized
  (let [{:keys [descriptors]} (run)]
    (is (every? #(true? (get % "authorized")) descriptors))
    ;; G6 — nothing executed
    (is (every? #(false? (get % "executed")) descriptors))))

(deftest test-pipeline-serviceops-only-t1-t2
  (let [{:keys [serviceops]} (run)]
    ;; only the T1 plan becomes a karakuri op; T3 (netflix) is member-submits → no op
    (is (= 1 (count serviceops)))
    (is (= "generic-saas-api" (:service (first serviceops))))
    (is (= "delete" (:verb (first serviceops))))))

(deftest test-pipeline-serviceops-valid-against-karakuri
  (let [{:keys [serviceops]} (run)
        lex (kb/lexicon (io/file (.getParentFile actor-dir) "karakuri" "lex" "serviceOp.edn"))]
    (doseq [op serviceops]
      (is (= [] (kb/validate-serviceop op lex))))))

(deftest test-pipeline-receipts-and-exactly-once
  (let [{:keys [receipt-datoms severed]} (run)]
    (is (pos? (count receipt-datoms)))
    ;; both T1+T3 authorized; only the T1 (:authorized-dry-run) advances the severed cursor
    (is (= #{"generic-saas-api"} severed))
    ;; G6 — every receipt executed=false
    (is (every? (fn [[_ _ a v]] (or (not= a ":kaiyaku.receipt/executed") (false? v))) receipt-datoms))))

(deftest test-pipeline-persist-roundtrip
  (let [p (str (System/getProperty "java.io.tmpdir") "/kaiyaku-pipeline-" (gensym) ".edn")]
    (try
      (let [r (pipeline/run+persist! {:nodes nodes :edges edges :catalog (catalog-by-id)
                                      :bundle bundle :now-epoch 1000}
                                     p {:tx-id "t1" :as-of "T0"})]
        (is (clojure.string/starts-with? (:receipt-cid r) "b"))
        (is (= 1 (count (k/read-log p))))
        (is (:ok (k/verify-chain p))))
      (finally (io/delete-file p true)))))

(deftest test-pipeline-unapproved-svc-refused
  ;; a capability that approves only netflix → the T1 tie is refused (G5 in the leash)
  (let [b (assoc bundle "approved" ["netflix"])
        {:keys [descriptors serviceops]} (pipeline/run {:nodes nodes :edges edges
                                                        :catalog (catalog-by-id) :bundle b
                                                        :now-epoch 1000 :as-of "T0"})
        by-svc (into {} (map (juxt #(get % "svc") identity) descriptors))]
    (is (true? (get (by-svc "netflix") "authorized")))
    (is (false? (get (by-svc "generic-saas-api") "authorized")))
    ;; the refused tie produces no karakuri op
    (is (empty? serviceops))))

(deftest test-member-report-honest
  (let [md (pipeline/member-report (run))]
    ;; dry-run honesty up front
    (is (clojure.string/includes? md "dry-run"))
    (is (clojure.string/includes? md "まだ何も実行されていません"))
    ;; both services appear with their disclosed procedure steps
    (is (clojure.string/includes? md "netflix"))
    (is (clojure.string/includes? md "generic-saas-api"))
    (is (clojure.string/includes? md "手順:"))
    ;; never claims execution
    (is (clojure.string/includes? md "executed: false"))
    (is (not (clojure.string/includes? md "executed: true")))))

(deftest test-member-report-flags-operator-verification
  ;; catalog entries are operator-verified=false → the ⚠ flag must appear
  (let [md (pipeline/member-report (run))]
    (is (clojure.string/includes? md "operator 検証が必要"))))

(deftest test-member-report-shows-refusal-reason
  ;; with a capability approving only netflix, the T1 tie is refused → its reason shows
  (let [b (assoc bundle "approved" ["netflix"])
        r (pipeline/run {:nodes nodes :edges edges :catalog (catalog-by-id)
                         :bundle b :now-epoch 1000 :as-of "T0"})
        md (pipeline/member-report r)]
    (is (clojure.string/includes? md "理由:"))
    (is (clojure.string/includes? md "allowlist"))))

(deftest test-run-seed-all-refused-honestly
  ;; the committed synthetic seed, run with NO capability → every severable tie is refused.
  (let [r (pipeline/run-seed actor-dir)]
    (is (pos? (count (:descriptors r))))
    (is (every? #(= ":refused" (get % "status")) (:descriptors r)))
    (is (empty? (:severed r)))
    (is (empty? (:serviceops r)))                ; nothing authorized → no karakuri op
    ;; the report is honest about it
    (let [md (pipeline/member-report r)]
      (is (clojure.string/includes? md "認可(dry-run) 0件")))))

(deftest test-run-seed-with-capability-authorizes
  ;; provide a capability approving a seed svc that is severable → it authorizes.
  (let [seed-svc "svc:saas-c"   ; api :available → T1, paid+low-usage → :sever in the seed
        b {"cacao_b64" "opaque" "aud" "did:web:etzhayyim.com" "capability" "service:cancel"
           "graph" "graph:kaiyaku" "exp" 9999999999 "nonce" "n" "approved" [seed-svc]}
        r (pipeline/run-seed actor-dir :bundle b :now-epoch 1000 :as-of "T0")
        by-svc (into {} (map (juxt #(get % "svc") identity) (:descriptors r)))]
    (when (contains? by-svc seed-svc)            ; seed contains this severable tie
      (is (true? (get (by-svc seed-svc) "authorized"))))))

;; ── cascade (依存) end-to-end ────────────────────────────────────────────────

(def cascade-nodes
  {member {":member/id" member ":member/label" "Test"}
   ;; a severable hub another tie stands on (SSO / payment dependency)
   "hub-svc" (node "hub-svc" ":none" ":permitted")
   "dependent-svc" (node "dependent-svc" ":none" ":permitted")})
(def cascade-edges
  [{":en/from" member ":en/to" "hub-svc" ":en/kind" ":subscribes"
    ":en/monthly-cost-jpy" 1500.0 ":en/usage-score" 5.0 ":en/last-used-days" 10.0}
   ;; dependent-svc DEPENDS-ON hub-svc → hub has a dependent → :sever downgrades to :review-cascade
   {":en/from" "dependent-svc" ":en/to" "hub-svc" ":en/kind" ":depends-on"}])

(deftest test-cascade-review-refused-end-to-end
  ;; a capability that APPROVES the hub must still be refused — cascade beats capability.
  (let [b {"cacao_b64" "opaque" "aud" "did:web:etzhayyim.com" "capability" "service:cancel"
           "graph" "graph:kaiyaku" "exp" 9999999999 "nonce" "n" "approved" ["hub-svc"]}
        r (pipeline/run {:nodes cascade-nodes :edges cascade-edges :catalog (catalog-by-id)
                         :bundle b :now-epoch 1000 :as-of "T0"})
        hub-plan (first (filter #(= "hub-svc" (get % "svc")) (:plans r)))
        hub-desc (first (filter #(= "hub-svc" (get % "svc")) (:descriptors r)))]
    ;; analyze downgraded :sever → :review-cascade because the hub has a dependent
    (is (= ":review-cascade" (get hub-plan "recommendation")))
    ;; the plan re-homes the dependency FIRST (before any cancel step)
    (is (= "rehome-dependency" (get (first (get hub-plan "steps")) "verb")))
    ;; even with the hub approved, dispatch REFUSES it (rehome first; cascade > capability)
    (is (false? (get hub-desc "authorized")))
    (is (clojure.string/includes? (get hub-desc "why") "re-homed BEFORE severance"))
    ;; → no karakuri op for a cascade-refused tie
    (is (empty? (:serviceops r)))))

;; ── machine-readable summary ─────────────────────────────────────────────────

(deftest test-summary-counts-and-shape
  (let [s (pipeline/summary (run))]
    (is (true? (:dry-run s)))
    (is (= (:total s) (count (:services s))))
    ;; counts partition the descriptors
    (is (= (:total s) (+ (count (filter #(= ":authorized-dry-run" (:status %)) (:services s)))
                         (:member-submits s)
                         (:refused s))))
    ;; G6 — every row executed=false
    (is (every? #(false? (:executed %)) (:services s)))
    ;; the T1 authorized tie surfaces as a karakuri op
    (is (= 1 (count (:serviceops s))))))

(deftest test-summary-edn-roundtrips
  ;; the EDN text parses back to the same data (machine-consumable, deterministic).
  (let [r (run)
        txt (pipeline/summary-edn r)
        ;; strip the comment line, read the form
        parsed (clojure.edn/read-string (clojure.string/replace txt #";;[^\n]*\n" ""))]
    (is (= (:total parsed) (:total (pipeline/summary r))))
    (is (true? (:dry-run parsed)))))

(deftest test-summary-seed-all-refused
  (let [s (pipeline/summary (pipeline/run-seed actor-dir))]
    (is (= (:total s) (:refused s)))         ; no capability → all refused
    (is (zero? (:authorized s)))
    (is (empty? (:serviceops s)))))

;; ── operator self-check (run → persist → audit verify) ──────────────────────

(deftest test-operator-self-check-seed
  (let [p (str (System/getProperty "java.io.tmpdir") "/kaiyaku-selfcheck-" (gensym) ".edn")]
    (try
      (let [c (pipeline/operator-self-check! actor-dir p)]
        (is (pos? (:severable c)))
        (is (= (:severable c) (:refused c)))     ; no capability → all refused
        (is (zero? (:authorized c)))
        (is (= (:severable c) (:receipts c)))    ; one receipt per severable tie
        (is (true? (:audit-clean? c)))           ; G6 — no live execution recorded
        (is (true? (:all-executed-false? c))))
      (finally (io/delete-file p true)))))

(deftest test-operator-self-check-with-capability-still-clean
  ;; even when a capability authorizes a tie, the audit log stays clean (executed=false).
  (let [p (str (System/getProperty "java.io.tmpdir") "/kaiyaku-selfcheck-" (gensym) ".edn")
        b {"cacao_b64" "opaque" "aud" "did:web:etzhayyim.com" "capability" "service:cancel"
           "graph" "graph:kaiyaku" "exp" 9999999999 "nonce" "n" "approved" ["svc:saas-c"]}]
    (try
      (let [c (pipeline/operator-self-check! actor-dir p :bundle b :now-epoch 1000)]
        (is (true? (:audit-clean? c)))
        (is (true? (:all-executed-false? c))))
      (finally (io/delete-file p true)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-pipeline)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
