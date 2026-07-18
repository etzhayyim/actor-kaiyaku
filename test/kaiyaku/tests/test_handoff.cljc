(ns kaiyaku.tests.test-handoff
  "kaiyaku 解約 — tate handoff ingest tests (wave 26). 1:1 Clojure port of tests/test_handoff.py.

  The compose loop closes: tate's make-kaiyaku-handoff output is parsed by kaiyaku's ingest
  and every :kaiyaku-routed clause flag becomes a notice-window candidate — round-trip across
  the two actors, no shared code beyond the EDN wire format."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kaiyaku.methods.handoff-ingest :as handoff]
            [tate.methods.terms-scan :as terms-scan]))

(def actor-dir (io/file "."))

(defn- cands [] (handoff/ingest (handoff/live-handoff-from-tate)))

(deftest test-roundtrip-count-matches-tate
  ;; Every :kaiyaku-routed tate flag arrives as exactly one candidate.
  (let [[docs _] (terms-scan/load-docs)
        res (terms-scan/scan docs (terms-scan/load-patterns))
        expect (filter #(= ":kaiyaku" (get % "route")) (get res "flags"))
        cs (cands)]
    (is (and (= (count cs) (count expect)) (>= (count cs) 10)))
    (is (= (set (map #(get % "clause") cs)) (set (map #(get % "clause") expect))))))

(deftest test-candidates-are-calendar-actions
  (doseq [c (cands)]
    (is (= ":calendar-notice-window" (get c "action")) (str c))
    (is (seq (get c "anchor")) (str c))   ; 開示アンカーは handoff を越えて保持される
    (is (str/starts-with? (get c "jurisdiction") ":"))))

(deftest test-datoms-emitted
  (let [cs (cands)
        text (handoff/to-datoms cs 5)]
    (is (= (count cs)
           (count (re-seq #":kaiyaku\.handoff/clause" text))))
    (is (str/includes? text ":kaiyaku.handoff/action :calendar-notice-window"))))

(deftest test-kaiyaku-claude-md-counts-in-sync
  ;; Wave 38: kaiyaku 側も CLAUDE.md のテスト数を実数照合 (同期封殺5本目).
  ;; Post py→cljc prune (ADR-2606160842): counts `(deftest …` across the .cljc test files
  ;; in tests/ (the .py files no longer exist) and matches CLAUDE.md's "# N tests, pure stdlib".
  (let [md (slurp (io/file actor-dir "CLAUDE.md"))
        n-tests (->> (.listFiles (io/file actor-dir "tests"))
                     (filter #(let [n (.getName %)]
                                (and (str/starts-with? n "test_") (str/ends-with? n ".cljc"))))
                     (map #(count (re-seq #"(?m)^\(deftest " (slurp %))))
                     (reduce + 0))
        m (re-find #"# (\d+) tests, pure stdlib" md)]
    (is (and m (= (Integer/parseInt (second m)) n-tests))
        (str "kaiyaku CLAUDE.md test count drift (actual " n-tests ")"))))
