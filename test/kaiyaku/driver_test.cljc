(ns kaiyaku.driver-test
  "kaiyaku 解約 — clj/ lane R1 capability + driver tests (ADR-2606112201 R1).
  The clj-native sibling of the methods/ lane's test_cap + test_driver, on
  real-keyword plan maps."
  (:require [clojure.test :refer [deftest is]]
            [kaiyaku.cap :as cap]
            [kaiyaku.driver :as driver]))

(def now 1000)

(defn bundle [approved & {:keys [exp] :or {exp 2000}}]
  {:cacao-b64 "opaque" :aud "did:web:etzhayyim.com" :capability cap/capability
   :graph cap/graph :exp exp :nonce "n" :approved (vec approved)})

(defn sever-plan [svc & {:keys [dependents tier] :or {dependents [] tier "T1"}}]
  (let [cancel-verb (case tier "T1" "api-cancel" "T2" "browser-cancel" "self-submit")]
    {:svc svc :svc-label svc :tier tier :recommendation :sever
     :notice-days 30 :penalty-jpy 1000
     :steps (-> (mapv (fn [d] {:verb "rehome-dependency" :detail (str "rehome " d) :mode :dry-run})
                      dependents)
                (conj {:verb cancel-verb :detail "cancel" :mode :dry-run})
                (conj {:verb "confirm-closure" :detail "verify" :mode :dry-run}))}))

;; ── cap ──────────────────────────────────────────────────────────────────────

(deftest cap-approved-and-usable
  (let [b (bundle ["svc:a"])]
    (is (true? (cap/approved? b "svc:a")))
    (is (false? (cap/approved? b "svc:z")))
    (is (first (cap/usable? b {:now-epoch now :svc-id "svc:a"})))
    (is (false? (first (cap/usable? b {:now-epoch 3000 :svc-id "svc:a"}))))   ; expired
    (is (false? (first (cap/usable? nil {:now-epoch now :svc-id "svc:a"}))))))  ; absent

(deftest cap-validate-rejects-bad-scope
  (is (cap/cap-error? (try (cap/validate-bundle (assoc (bundle ["svc:a"]) :graph "graph:other"))
                           (catch clojure.lang.ExceptionInfo e e))))
  (is (cap/cap-error? (try (cap/validate-bundle (assoc (bundle ["svc:a"]) :capability "datom:transact"))
                           (catch clojure.lang.ExceptionInfo e e))))
  ;; a well-formed bundle passes through
  (is (map? (cap/validate-bundle (bundle ["svc:a"])))))

;; ── driver ───────────────────────────────────────────────────────────────────

(deftest driver-no-capability-refused
  (let [d (driver/dispatch (sever-plan "svc:a") {:bundle nil :now-epoch now})]
    (is (false? (:authorized d)))
    (is (= :refused (:status d)))
    (is (false? (:executed d)))
    (is (false? (:server-signed d)))))

(deftest driver-not-approved-refused
  (let [d (driver/dispatch (sever-plan "svc:b") {:bundle (bundle ["svc:a"]) :now-epoch now})]
    (is (false? (:authorized d)))
    (is (re-find #"not in the member-approved allowlist" (:why d)))))

(deftest driver-approved-authorized-never-executed
  (let [d (driver/dispatch (sever-plan "svc:a") {:bundle (bundle ["svc:a"]) :now-epoch now})]
    (is (true? (:authorized d)))
    (is (= :authorized-dry-run (:status d)))
    (is (false? (:executed d)))
    (is (= "member" (:authorized-by d)))
    (is (= 30 (:notice-days d)))))

(deftest driver-review-cascade-never-dispatched
  (let [plan (assoc (sever-plan "svc:hub" :dependents ["svc:dep"]) :recommendation :review-cascade)
        d (driver/dispatch plan {:bundle (bundle ["svc:hub"]) :now-epoch now})]
    (is (false? (:authorized d)))
    (is (re-find #"re-homed BEFORE severance" (:why d)))))

(deftest driver-cascade-order-asserted
  (let [good (sever-plan "svc:a" :dependents ["svc:dep"])]
    (is (= good (driver/assert-cascade-order good))))
  (let [bad {:svc "svc:a" :steps [{:verb "api-cancel"} {:verb "rehome-dependency"}]}]
    (is (thrown? clojure.lang.ExceptionInfo (driver/assert-cascade-order bad)))))

(deftest driver-t3-member-submits
  (let [d (driver/dispatch (sever-plan "svc:s" :tier "T3") {:bundle (bundle ["svc:s"]) :now-epoch now})]
    (is (true? (:authorized d)))
    (is (= :member-submits (:status d)))
    (is (false? (:executed d)))))

(deftest driver-batch-exactly-once
  (let [b (bundle ["svc:a" "svc:b"])
        plans [(sever-plan "svc:a") (sever-plan "svc:b")]
        run1 (driver/dispatch-batch plans {:bundle b :now-epoch now})]
    (is (= #{"svc:a" "svc:b"} (:severed run1)))
    (let [run2 (driver/dispatch-batch plans {:bundle b :now-epoch now
                                             :already-severed (:severed run1)})]
      (is (empty? (:severed run2)))
      (is (every? #(= :already-severed (:status %)) (:results run2))))))
