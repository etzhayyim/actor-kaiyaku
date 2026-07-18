(ns kaiyaku.tests.test-audit
  "kaiyaku 解約 — G9 audit READ-side tests (ADR-2606112201 R1).

  Closes the audit loop: persist receipts (receipt.cljc) → read them back (audit.cljc).
    - receipts fold back from the append-only log into per-receipt entity views
    - per-svc query works; booleans round-trip (authorized/executed/server-signed)
    - the standing G6 verification: no receipt ever records a live execution (executed=0,
      server-signed=0) → no-live-execution? true"
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [kaiyaku.methods.audit :as audit]
            [kaiyaku.methods.receipt :as receipt]))

(defn- tmp [] (str (System/getProperty "java.io.tmpdir") "/kaiyaku-audit-" (gensym) ".edn"))

(def descriptors
  [{"svc" "netflix" "authorized" true "status" ":authorized-dry-run" "authorized_by" "member"}
   {"svc" "gym" "authorized" false "status" ":refused"}
   {"svc" "saas" "authorized" true "status" ":authorized-dry-run" "authorized_by" "member"}])

(defn- persisted []
  (let [p (tmp)]
    (receipt/persist-receipts! descriptors p {:tx-id "t1" :as-of "T0"})
    p))

(deftest test-receipts-fold-back
  (let [p (persisted)]
    (try
      (let [rs (audit/receipts p)]
        (is (= 3 (count rs)))
        (is (= #{"netflix" "gym" "saas"} (set (map #(get % ":kaiyaku.receipt/svc") rs))))
        ;; booleans round-trip as real Booleans
        (is (every? #(contains? #{true false} (get % ":kaiyaku.receipt/authorized")) rs)))
      (finally (io/delete-file p true)))))

(deftest test-receipts-for-svc
  (let [p (persisted)]
    (try
      (let [rs (audit/receipts p)
            nfx (audit/receipts-for-svc rs "netflix")]
        (is (= 1 (count nfx)))
        (is (true? (get (first nfx) ":kaiyaku.receipt/authorized"))))
      (finally (io/delete-file p true)))))

(deftest test-audit-summary
  (let [p (persisted)]
    (try
      (let [s (audit/audit-summary (audit/receipts p))]
        (is (= 3 (:total s)))
        (is (= 2 (:authorized s)))
        (is (= 1 (:refused s)))
        (is (zero? (:executed s)))           ; G6
        (is (zero? (:server-signed s))))     ; G3
      (finally (io/delete-file p true)))))

(deftest test-no-live-execution-standing-verification
  (let [p (persisted)]
    (try
      (is (true? (audit/no-live-execution? (audit/receipts p))))
      (finally (io/delete-file p true)))))

(deftest test-no-live-execution-detects-violation
  ;; if a (hypothetical) receipt ever claimed executed=true, the verification must FAIL.
  (let [tainted [{":kaiyaku.receipt/svc" "x" ":kaiyaku.receipt/executed" true
                  ":kaiyaku.receipt/server-signed" false ":kaiyaku.receipt/status" ":authorized-dry-run"}]]
    (is (false? (audit/no-live-execution? tainted)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-audit)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
