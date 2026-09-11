(ns kaiyaku.tests.test-maturity
  "kaiyaku 解約 — MATURITY scorecard tests (ADR-2606112201 R1).

  - the generated scorecard reports the honest R1 state (all six components, the
    catalog coverage, the G6/operator-verified gap)
  - FRESHNESS: the committed MATURITY.md equals the generator output, so it can
    never silently drift from the manifest/catalog it summarizes."
  (:require [kotoba.lang.text] [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [kaiyaku.methods.maturity :as maturity]))

(def actor-dir (io/file "."))

(deftest test-scorecard-content
  (let [md (maturity/generate actor-dir)]
    ;; all six R1 components present
    (doseq [c ["capability" "driver" "catalog" "receipt" "handoff" "pipeline"]]
      (is (kotoba.lang.text/includes? md c) (str "missing component " c)))
    ;; honest gaps surfaced
    (is (kotoba.lang.text/includes? md "operator-verified: 0"))
    (is (kotoba.lang.text/includes? md "executed=false"))
    (is (kotoba.lang.text/includes? md "category coverage: 15/15"))
    ;; all nine gates listed
    (doseq [g ["G1" "G2" "G3" "G4" "G5" "G6" "G7" "G8" "G9"]]
      (is (kotoba.lang.text/includes? md g)))))

(deftest test-maturity-md-fresh
  ;; the committed MATURITY.md must match the generator (regenerate if this fails:
  ;; `bb 20-actors/kaiyaku/methods/maturity.cljc` from repo root, or run -main).
  (let [f (io/file actor-dir "MATURITY.md")]
    (is (.exists f) "MATURITY.md must be committed")
    (is (= (slurp f) (maturity/generate actor-dir))
        "MATURITY.md is stale — regenerate via methods/maturity.cljc -main")))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-maturity)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
