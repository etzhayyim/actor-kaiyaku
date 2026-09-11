(ns kaiyaku.tests.test-driver
  "kaiyaku 解約 — R1 severance driver tests (ADR-2606112201 R1).

  Proves the four invariants empirically (not just by documentation):
    - G3 no-server-key: no capability / expired / wrong-graph / not-approved → refused;
      every descriptor is executed=false, server_signed=false
    - G5 in-the-leash: a tie absent from the capability's `approved` allowlist is refused
      even under a valid, unexpired bundle
    - cascade ordering: a :review-cascade plan is never live-dispatched; a :sever plan with
      a rehome step AFTER the cancel step trips assert-cascade-order
    - exactly-once: a svc already in the cursor is a no-op; the batch advances the cursor"
  (:require [clojure.test :refer [deftest is run-tests]]
            [kaiyaku.methods.cap :as cap]
            [kaiyaku.methods.driver :as driver]))

;; ── fixtures ────────────────────────────────────────────────────────────────

(def now 1000)
(def later-exp 2000)   ; > now → live
(def past-exp 500)     ; < now → expired

(defn bundle
  "A valid member-presented capability over `approved` svc-ids."
  [approved & {:keys [exp graph] :or {exp later-exp graph cap/graph}}]
  {"cacao_b64" "b64-opaque-member-signed-bytes"
   "aud" "did:web:etzhayyim.com"
   "capability" cap/capability
   "graph" graph
   "exp" exp
   "nonce" "deadbeef"
   "approved" (vec approved)})

(defn sever-plan
  "A minimal :sever plan (T1) for svc, with optional dependents (rehome steps first)."
  [svc & {:keys [dependents tier] :or {dependents [] tier "T1"}}]
  (let [cancel-verb (case tier "T1" "api-cancel" "T2" "browser-cancel" "self-submit")]
    {"svc" svc "svc_label" (str "Service " svc) "tier" tier "recommendation" ":sever"
     "notice_days" 30 "penalty_jpy" 1000
     "steps" (-> (mapv (fn [d] {"verb" "rehome-dependency" "detail" (str "rehome " d) "mode" "dry-run"})
                       dependents)
                 (conj {"verb" cancel-verb "detail" "cancel" "mode" "dry-run"})
                 (conj {"verb" "confirm-closure" "detail" "verify" "mode" "dry-run"}))}))

;; ── G3 / G5 — capability gating ─────────────────────────────────────────────

(deftest test-no-capability-refused
  (let [d (driver/dispatch (sever-plan "svc:a") {:bundle nil :now-epoch now})]
    (is (false? (get d "authorized")))
    (is (= ":refused" (get d "status")))
    (is (false? (get d "executed")))
    (is (false? (get d "server_signed")))))

(deftest test-expired-capability-refused
  (let [d (driver/dispatch (sever-plan "svc:a")
                           {:bundle (bundle ["svc:a"] :exp past-exp) :now-epoch now})]
    (is (false? (get d "authorized")))
    (is (re-find #"expired" (get d "why")))))

(deftest test-cap-usable-and-approved
  ;; cap/usable? is the pure leash check; cap/approved? is the G5 allowlist gate.
  (let [b (bundle ["svc:a" "svc:c"])]
    (is (true? (cap/approved? b "svc:a")))
    (is (false? (cap/approved? b "svc:z")))
    (is (false? (cap/approved? nil "svc:a")))
    (is (first (cap/usable? b {:now-epoch now :svc-id "svc:a"})))
    (is (false? (first (cap/usable? b {:now-epoch later-exp :svc-id "svc:a"}))))   ; now == exp → expired
    (is (false? (first (cap/usable? b {:now-epoch now :svc-id "svc:z"}))))         ; off-allowlist
    (is (false? (first (cap/usable? nil {:now-epoch now :svc-id "svc:a"}))))))     ; absent

(deftest test-not-approved-refused
  ;; G5 in the leash: valid, unexpired bundle, but svc:b was NOT member-approved.
  (let [b (bundle ["svc:a"])
        d (driver/dispatch (sever-plan "svc:b") {:bundle b :now-epoch now})]
    (is (false? (get d "authorized")))
    (is (re-find #"not in the member-approved allowlist" (get d "why")))))

(deftest test-approved-authorized-but-never-executed
  (let [b (bundle ["svc:a"])
        d (driver/dispatch (sever-plan "svc:a") {:bundle b :now-epoch now})]
    (is (true? (get d "authorized")))
    (is (= ":authorized-dry-run" (get d "status")))
    ;; the whole point: authorized != executed (live driver is post-R1)
    (is (false? (get d "executed")))
    (is (false? (get d "server_signed")))
    (is (= "member" (get d "authorized_by")))
    ;; G8 — cost-of-severance carried into the authorization
    (is (= 30 (get d "notice_days")))
    (is (= 1000 (get d "penalty_jpy")))))

;; ── cascade ordering (依存) ─────────────────────────────────────────────────

(deftest test-review-cascade-never-live-dispatched
  (let [b (bundle ["svc:hub"])
        plan (assoc (sever-plan "svc:hub" :dependents ["svc:dep"])
                    "recommendation" ":review-cascade")
        d (driver/dispatch plan {:bundle b :now-epoch now})]
    (is (false? (get d "authorized")))
    (is (re-find #"re-homed BEFORE severance" (get d "why")))))

(deftest test-cascade-order-asserted
  ;; rehome step BEFORE cancel → ok; AFTER cancel → raises.
  (let [good (sever-plan "svc:a" :dependents ["svc:dep"])]   ; rehome..cancel..confirm (ok)
    (is (= good (driver/assert-cascade-order good))))
  (let [bad {"svc" "svc:a" "steps" [{"verb" "api-cancel"}
                                    {"verb" "rehome-dependency"}]}]
    (is (thrown? clojure.lang.ExceptionInfo (driver/assert-cascade-order bad)))))

;; ── exactly-once (冪等) ─────────────────────────────────────────────────────

(deftest test-already-severed-noop
  (let [b (bundle ["svc:a"])
        d (driver/dispatch (sever-plan "svc:a")
                           {:bundle b :now-epoch now :already-severed #{"svc:a"}})]
    (is (false? (get d "authorized")))
    (is (= ":already-severed" (get d "status")))))

(deftest test-batch-threads-cursor-and-is-resume-safe
  (let [b (bundle ["svc:a" "svc:b"])
        plans [(sever-plan "svc:a") (sever-plan "svc:b")]
        run1 (driver/dispatch-batch plans {:bundle b :now-epoch now})]
    ;; first run authorizes both, advancing the cursor
    (is (= #{"svc:a" "svc:b"} (:severed run1)))
    (is (every? #(true? (get % "authorized")) (:results run1)))
    ;; resume with the prior :severed → every tie is now a no-op (exactly-once)
    (let [run2 (driver/dispatch-batch plans {:bundle b :now-epoch now
                                             :already-severed (:severed run1)})]
      (is (empty? (:severed run2)))
      (is (every? #(= ":already-severed" (get % "status")) (:results run2))))))

;; ── T3 is never sent ────────────────────────────────────────────────────────

(deftest test-t3-member-submits-never-executed
  (let [b (bundle ["svc:self"])
        d (driver/dispatch (sever-plan "svc:self" :tier "T3") {:bundle b :now-epoch now})]
    (is (true? (get d "authorized")))
    (is (= ":member-submits" (get d "status")))
    (is (false? (get d "executed")))))

;; ── catalog-enriched descriptor (catalog → plan → driver) ───────────────────

(defn- enriched-plan
  "A :sever plan carrying a catalog/enrich-plan-shaped \"catalog\" submap."
  [svc & {:keys [operator-verified g8-drift]
          :or {operator-verified false g8-drift nil}}]
  (assoc (sever-plan svc)
         "catalog" (cond-> (array-map
                            "tier" "T3"
                            "self_submit_steps" ["step 1" "step 2"]
                            "notice_days" 0
                            "penalty_jpy" 0
                            "disclosed_source" "https://help.example/cancel"
                            "operator_verified" operator-verified)
                     g8-drift (assoc "g8_drift" g8-drift))))

(deftest test-descriptor-surfaces-disclosed-procedure
  (let [b (bundle ["svc:a"])
        d (driver/dispatch (enriched-plan "svc:a") {:bundle b :now-epoch now})]
    (is (true? (get d "authorized")))
    (is (map? (get d "disclosed_procedure")))
    (is (seq (get-in d ["disclosed_procedure" "self_submit_steps"])))
    ;; G6 honesty — a representative (unverified) procedure is flagged even when authorized
    (is (true? (get d "operator_verification_required")))
    ;; still never executed
    (is (false? (get d "executed")))))

(deftest test-descriptor-g8-ack-required-on-drift
  (let [b (bundle ["svc:a"])
        d (driver/dispatch (enriched-plan "svc:a" :g8-drift ["penalty_jpy ledger=0 catalog=5000"])
                           {:bundle b :now-epoch now})]
    (is (true? (get d "g8_ack_required")))
    (is (seq (get d "g8_drift")))))

(deftest test-descriptor-no-catalog-keys-when-unenriched
  ;; a plain (unenriched) plan must NOT gain catalog keys — additive only.
  (let [b (bundle ["svc:a"])
        d (driver/dispatch (sever-plan "svc:a") {:bundle b :now-epoch now})]
    (is (nil? (get d "disclosed_procedure")))
    (is (nil? (get d "g8_ack_required")))
    (is (nil? (get d "operator_verification_required")))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-driver)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
