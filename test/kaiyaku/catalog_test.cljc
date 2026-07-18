(ns kaiyaku.catalog-test
  "kaiyaku 解約 — clj/ lane catalog tests (ADR-2606112201 R1, sibling of the
  methods/ lane test_catalog). Proves the clj catalog is functionally equivalent:
  loads the shared EDN, derive-tier ≡ plan/select-tier, enrich is additive + G8-drift,
  validate honest; and the driver surfaces the enriched procedure."
  (:require [clojure.test :refer [deftest is]]
            [kaiyaku.catalog :as catalog]
            [kaiyaku.plan :as plan]
            [kaiyaku.driver :as driver]))

(def catalog-file "data/cancel-procedures.kotoba.edn")
(defn- entries [] (catalog/load-file* catalog-file))

(deftest catalog-loads-and-validates
  (let [es (entries)
        {:keys [ok? errors]} (catalog/validate es)]
    (is (>= (count es) 20))
    (is ok? (str "catalog errors: " (pr-str errors)))
    (is (contains? (catalog/by-id es) "netflix"))))

(deftest derive-tier-matches-planner
  ;; G3 — the clj catalog tier and the clj planner's routing must not drift.
  (doseq [e (entries)]
    (is (= (catalog/derive-tier e)
           (plan/select-tier (catalog/->svc-node e)))
        (str (:proc/svc-id e) ": tier drift"))))

(deftest enrich-plan-additive-and-drift
  (let [bi (catalog/by-id (entries))
        ;; netflix catalog notice/penalty are 0/0 → no drift when ledger is 0/0
        p (catalog/enrich-plan {:svc "netflix" :tier "T3" :recommendation :sever
                                :notice-days 0 :penalty-jpy 0} bi)]
    (is (true? (:catalog-coverage p)))
    (is (seq (get-in p [:catalog :self-submit-steps])))
    (is (= :sever (:recommendation p)))                ; original field untouched
    ;; adobe-cc ETF > 0 vs a ledger 0 → g8-drift shown
    (let [a (catalog/enrich-plan {:svc "adobe-cc" :tier "T3" :recommendation :sever
                                  :notice-days 0 :penalty-jpy 0} bi)]
      (is (pos? (get-in a [:catalog :penalty-jpy])))
      (is (seq (get-in a [:catalog :g8-drift]))))
    ;; an svc not in the catalog → honest coverage gap
    (is (false? (:catalog-coverage (catalog/enrich-plan {:svc "svc:unknown"} bi))))))

(deftest driver-surfaces-disclosed-procedure
  (let [bi (catalog/by-id (entries))
        bundle {:capability "service:cancel" :graph "graph:kaiyaku" :aud "did:web:etzhayyim.com"
                :exp 9999999999 :nonce "n" :approved ["netflix"]}
        plan (catalog/enrich-plan {:svc "netflix" :svc-label "Netflix" :tier "T3"
                                   :recommendation :sever :notice-days 0 :penalty-jpy 0
                                   :steps [{:verb "self-submit" :detail "x" :mode :dry-run}]}
                                  bi)
        d (driver/dispatch plan {:bundle bundle :now-epoch 1000})]
    (is (true? (:authorized d)))
    (is (map? (:disclosed-procedure d)))
    (is (true? (:operator-verification-required d)))   ; G6 honesty
    (is (false? (:executed d)))))
