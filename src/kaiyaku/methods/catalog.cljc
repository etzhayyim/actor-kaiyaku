#!/usr/bin/env bb
;; kaiyaku 解約 — real-service cancellation-procedure CATALOG loader + validator.
(ns kaiyaku.methods.catalog
  "catalog.cljc — kaiyaku 解約 R1 cancellation-procedure catalog (ADR-2606112201 R1).

  The R1 DATA leg. R0's 縁-ledger is 9 synthetic ties; this loads the
  `data/cancel-procedures.kotoba.edn` catalog of REAL services + their disclosed
  解約/退会 procedures, so a severance plan can carry the actual self-submit
  steps + cost-of-severance (notice / 違約金) instead of a placeholder.

  This namespace is a clean standard-EDN consumer (real :proc/* keywords, read
  with clojure.edn) — NOT the Python-parity string-keyword ledger parser in
  analyze.cljc. It is decoupled: the catalog is a REFERENCE the plan/driver
  consult by svc-id, not a ledger.

  HONESTY GATES (enforced by validate, proven by tests):
    G3 — derive-tier mirrors plan/select-tier EXACTLY; a :browser stance of
      :prohibited / :unknown can NEVER yield T2 (validate raises on a T2 claim
      over a non-:permitted browser stance). self-submit-steps may contain NO
      detection-evasion verb.
    G8 — every entry carries :proc/notice-days + :proc/penalty-jpy (the
      disclosed cost-of-severance, surfaced into the plan, never planned around).
    G6 — every entry is :operator-verified false (a :representative disclosed
      SHAPE, not a live ToS assertion; an operator must verify before live use).
    N1 — every entry is a SERVICE (svc-id), never a person.

  Deterministic; pure fns; file I/O only at the #?(:clj …) load edge. Portable .cljc."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io])))

;; G3 — the same unrepresentable set as plan/make-step. A disclosed official
;; procedure never contains one of these; validate refuses an entry that does.
(def evasion-tokens
  #{"captcha-solve" "proxy-rotate" "stealth" "rate-limit-bypass"
    "fingerprint-spoof" "ip-rotate" "anti-bot-bypass"})

(def required-keys
  [:proc/svc-id :proc/name :proc/category :proc/region :proc/cancel
   :proc/notice-days :proc/penalty-jpy :proc/self-submit-steps
   :proc/disclosed-source :proc/operator-verified :proc/sourcing])

;; ── load (file edge) ────────────────────────────────────────────────────────

#?(:clj
   (defn load-file*
     "Read the catalog EDN → vector of :proc/* entry maps. File I/O only here."
     [path]
     (edn/read-string (slurp (str path)))))

(defn by-id
  "svc-id → entry map."
  [entries]
  (into {} (map (juxt :proc/svc-id identity)) entries))

;; ── tier derivation (MUST mirror plan/select-tier) ──────────────────────────

(defn derive-tier
  "Safest-first adapter tier for a catalog entry — byte-identical logic to
  methods/plan.cljc select-tier, on real-keyword values:
    cancel api :available → T1 · browser :permitted → T2 · else → T3.
  A :prohibited / :unknown browser stance can never reach T2 (G3)."
  [entry]
  (let [cancel (or (:proc/cancel entry) {})]
    (cond
      (= (:api cancel) :available)     "T1"
      (= (:browser cancel) :permitted) "T2"
      :else                            "T3")))

(defn ->svc-node
  "Convert a catalog entry into the ledger's STRING-keyword :svc/* node shape
  (the methods/ tree's house format), so plan/select-tier can run on it directly
  — this is the bridge the cross-consistency test exercises."
  [entry]
  (let [c (:proc/cancel entry)]
    {":svc/id" (:proc/svc-id entry)
     ":svc/label" (:proc/name entry)
     ":svc/notice-days" (:proc/notice-days entry)
     ":svc/penalty-jpy" (:proc/penalty-jpy entry)
     ":svc/cancel" {":api" (str (:api c))
                    ":browser" (str (:browser c))
                    ":self-submit" (boolean (:self-submit c))}}))

;; ── validate (the honesty gates, in code) ───────────────────────────────────

(defn- step-text [steps] (str/lower-case (str/join " " (map str steps))))

(defn validate-entry
  "Returns a vector of error strings for one entry (empty = ok)."
  [entry]
  (let [errs (transient [])
        sid (:proc/svc-id entry)
        cancel (:proc/cancel entry)
        browser (:browser cancel)]
    (doseq [k required-keys]
      (when-not (contains? entry k)
        (conj! errs (str sid ": missing " k))))
    ;; G3 — a non-:permitted browser stance must not derive T2
    (when (and (= "T2" (derive-tier entry)) (not= browser :permitted))
      (conj! errs (str sid ": G3 — tier T2 over a non-:permitted browser stance " (pr-str browser))))
    ;; G3 — no evasion verb in the disclosed steps
    (let [txt (step-text (:proc/self-submit-steps entry))]
      (doseq [tok evasion-tokens]
        (when (str/includes? txt tok)
          (conj! errs (str sid ": G3 — evasion token '" tok "' in self-submit-steps")))))
    ;; G8 — cost-of-severance must be numbers
    (when-not (number? (:proc/notice-days entry))
      (conj! errs (str sid ": G8 — notice-days must be a number")))
    (when-not (number? (:proc/penalty-jpy entry))
      (conj! errs (str sid ": G8 — penalty-jpy must be a number")))
    ;; G6 — representative + unverified at R0
    (when (not= false (:proc/operator-verified entry))
      (conj! errs (str sid ": G6 — operator-verified must be false at R0 (:representative catalog)")))
    (when (not= :representative (:proc/sourcing entry))
      (conj! errs (str sid ": sourcing must be :representative at R0")))
    (persistent! errs)))

(defn validate
  "Validate the whole catalog. Returns {:ok? bool :errors [..]}."
  [entries]
  (let [errs (vec (mapcat validate-entry entries))]
    {:ok? (empty? errs) :errors errs}))

;; ── coverage report ─────────────────────────────────────────────────────────

(defn coverage
  "Honest catalog coverage: counts by tier / region / category, and verified vs
  unverified (always 0 verified at R0 — surfaced, not hidden)."
  [entries]
  {:total (count entries)
   :by-tier (frequencies (map derive-tier entries))
   :by-region (frequencies (map :proc/region entries))
   :by-category (frequencies (map :proc/category entries))
   :operator-verified (count (filter :proc/operator-verified entries))
   :with-cost-of-severance (count (filter #(or (pos? (:proc/notice-days %))
                                               (pos? (:proc/penalty-jpy %))) entries))})

(defn coverage-report
  "Markdown coverage summary (honest about the operator-verified gap)."
  [entries]
  (let [c (coverage entries)]
    (str "# kaiyaku cancellation-procedure catalog — coverage (R1, :representative)\n\n"
         "- total services: " (:total c) "\n"
         "- by tier: " (pr-str (:by-tier c)) "\n"
         "- by region: " (pr-str (:by-region c)) "\n"
         "- with disclosed cost-of-severance (notice/違約金): " (:with-cost-of-severance c) "\n"
         "- **operator-verified: " (:operator-verified c) " / " (:total c)
         "** (G6: every entry must be operator-verified before live use)\n")))

;; ── enrich a severance plan with the disclosed real procedure ───────────────

(defn enrich-plan
  "ADDITIVELY enrich a plan map (string keys, from plan/build-plan) with the
  catalog's disclosed real procedure for its svc — WITHOUT mutating any existing
  plan field (so plan/build-plan's byte-parity is untouched). When the plan's
  svc has a catalog entry it gains a \"catalog\" submap carrying the disclosed
  self-submit steps + cost-of-severance + source + the operator-verified flag;
  otherwise it gains \"catalog_coverage\" false (the gap is surfaced, not hidden).

  G8 honesty: if the catalog's disclosed notice/penalty DIFFER from the plan's
  (ledger-sourced) values, a \"g8_drift\" note is attached — kaiyaku never
  silently reconciles a cost-of-severance discrepancy; it shows both."
  [plan by-id]
  (let [svc (get plan "svc")
        e (get by-id svc)]
    (if-not e
      (assoc plan "catalog_coverage" false)
      (let [c-notice (:proc/notice-days e)
            c-penalty (:proc/penalty-jpy e)
            drift (cond-> []
                    (not= c-notice (get plan "notice_days"))
                    (conj (str "notice_days ledger=" (get plan "notice_days")
                               " catalog=" c-notice))
                    (not= c-penalty (get plan "penalty_jpy"))
                    (conj (str "penalty_jpy ledger=" (get plan "penalty_jpy")
                               " catalog=" c-penalty)))]
        (cond-> (assoc plan
                       "catalog_coverage" true
                       "catalog" (array-map
                                  "tier" (derive-tier e)
                                  "self_submit_steps" (vec (:proc/self-submit-steps e))
                                  "notice_days" c-notice
                                  "penalty_jpy" c-penalty
                                  "disclosed_source" (:proc/disclosed-source e)
                                  "operator_verified" (:proc/operator-verified e)))
          (seq drift) (assoc-in ["catalog" "g8_drift"] (vec drift)))))))

(defn enrich-plans
  "Enrich a vector of plans against the catalog (by-id)."
  [plans by-id]
  (mapv #(enrich-plan % by-id) plans))

;; ── catalog coverage of a set of ledger svc-ids (the growth worklist) ───────

(defn coverage-of
  "Given the svc-ids that appear in a (real, G7-gated) ledger, report which have
  a catalog procedure and which are GAPS — the prioritized worklist for growing
  the catalog (the uchiwake crosscheck pattern). Honest: the R0 synthetic seed
  ids never match the real catalog ids, so on the seed this reports 0% — the gap
  is the point (the catalog grows as real ledgers arrive)."
  [svc-ids by-id]
  (let [ids (vec (distinct svc-ids))
        covered (filterv #(contains? by-id %) ids)
        gaps (filterv #(not (contains? by-id %)) ids)]
    {:total (count ids)
     :covered covered
     :gaps gaps
     :pct (if (zero? (count ids)) 0.0
              (double (/ (Math/round (* 1000.0 (/ (count covered) (count ids)))) 10.0)))}))

;; ── category-coverage worklist (the catalog-growth target) ──────────────────

(def common-subscription-categories
  "A curated set of common consumer-subscription categories the catalog aims to
  cover. Honest target for the growth worklist — NOT a claim of completeness."
  #{:streaming :music :gaming :membership :productivity :storage :news :telecom
    :fitness :ai :security :vpn :design :dev :food-delivery})

(defn category-gaps
  "Which common subscription categories the catalog covers vs still misses — the
  growth worklist by category (distinct from coverage-of, which is per ledger
  svc-id). Returns {:covered #{…} :missing #{…} :pct …}."
  ([entries] (category-gaps entries common-subscription-categories))
  ([entries targets]
   (let [present (set (map :proc/category entries))
         covered (set/intersection targets present)
         missing (set/difference targets present)]
     {:covered covered
      :missing missing
      :pct (if (empty? targets) 0.0
               (double (/ (Math/round (* 1000.0 (/ (count covered) (count targets)))) 10.0)))})))

#?(:clj
   (defn -main
     "CLI: validate the catalog + print coverage (file I/O at the edge)."
     [& argv]
     (let [here (-> *file* io/file .getParentFile .getParentFile)
           path (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                  (io/file (first argv))
                  (io/file here "data" "cancel-procedures.kotoba.edn"))
           entries (load-file* path)
           {:keys [ok? errors]} (validate entries)]
       (print (coverage-report entries))
       (if ok?
         (do (println "validate: OK (" (count entries) "entries)") 0)
         (do (println "validate: FAIL")
             (doseq [e errors] (println "  -" e))
             1)))))
