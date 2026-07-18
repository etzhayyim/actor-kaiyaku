(ns kaiyaku.methods.test-datom-emit
  "kaiyaku 解約 — Datom-emit tests (ADR-2606112201), 1:1 port of the deferred datom tests in
  tests/test_analyze.py (test_datoms_ground_and_transient + test_determinism)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [kaiyaku.methods.analyze :as analyze]
            [kaiyaku.methods.datom-emit :as datom-emit]))

(def seed
  (str "."
       "/data/seed-en-ledger.kotoba.edn"))

(defn- load-seed [] (analyze/load-file* seed))

(deftest test-datoms-ground-and-transient
  (let [{:keys [nodes edges]} (load-seed)
        res (analyze/analyze nodes edges)
        text (datom-emit/emit nodes edges res 7)]
    (is (and (str/includes? text ":svc/label") (str/includes? text ":en/monthly-cost-jpy")))
    (is (str/includes? text ":bond/is-transient true")
        "derived readouts must be flagged transient (G2)")
    (is (str/includes? text ":enkiri/recommendation"))
    ;; every derived line sits under a transient-flagged entity; no plan executes here
    (is (not (str/includes? text "execute")))))

(deftest test-determinism
  (let [{:keys [nodes edges]} (load-seed)
        a (datom-emit/emit nodes edges (analyze/analyze nodes edges) 1)
        b (datom-emit/emit nodes edges (analyze/analyze nodes edges) 1)]
    (is (= a b))))
