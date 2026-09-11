(ns kaiyaku.tests.test-karakuri-bridge
  "kaiyaku 解約 — kaiyaku→karakuri ServiceOp handoff tests (ADR-2606112201 R1 ⇄ 2606039200).

  Proves the cross-actor seam holds against karakuri's OWN lexicon (no drift):
    - tier scheme parity: kaiyaku T1/T2/T3 maps EXACTLY onto karakuri's adapterTier enum
    - a kaiyaku plan → a VALID karakuri serviceOp (validate against the lexicon enums)
    - a 解約 is a 'delete' (karakuri's verb enum has no 'cancel'); destructive + dryRun + G5
    - T3 self-submit → nil (member's manual procedure, not a karakuri op)
    - the validator is real (catches a bad adapterTier / non-const dryRun)"
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [kaiyaku.methods.karakuri-bridge :as kb]))

;; repo-root-relative path works under run_tests.sh (cwd = repo root); fall back via actor-dir.
(def lex-file
  (let [root (io/file "orgs/etzhayyim/com-etzhayyim-karakuri/lex/serviceOp.edn")]
    (if (.exists root) root
        (io/file (-> *file* io/file .getParentFile .getParentFile .getParentFile)
                 "karakuri" "lex" "serviceOp.edn"))))

(defn- lex [] (kb/lexicon lex-file))

(deftest test-tier-scheme-aligned
  (is (kb/tier-scheme-aligned? (lex))
      "kaiyaku tiers must map exactly onto karakuri's adapterTier enum"))

(deftest test-plan-to-serviceop-valid
  (let [l (lex)]
    (doseq [tier ["T1" "T2"]]
      (let [op (kb/plan->serviceop {"svc" "svc:x" "tier" tier})]
        (is (= [] (kb/validate-serviceop op l)) (str tier " op invalid"))
        (is (= "delete" (:verb op)))          ; 解約 = delete (no 'cancel' verb)
        (is (true? (:destructive op)))
        (is (true? (:dryRun op)))             ; G6
        (is (= "awaiting-member-sig" (:mutateGate op)))   ; G5
        (is (contains? (kb/adapter-enum l) (:adapterTier op)))))))

(deftest test-t3-is-member-submits-not-an-op
  (is (nil? (kb/plan->serviceop {"svc" "svc:gym" "tier" "T3"}))))

(deftest test-noun-override-for-account-退会
  (let [op (kb/plan->serviceop {"svc" "svc:sns" "tier" "T1"} {:noun "account"})]
    (is (= "account" (:noun op)))))

(deftest test-validator-catches-bad-op
  (let [l (lex)
        good (kb/plan->serviceop {"svc" "svc:x" "tier" "T1"})]
    (is (= [] (kb/validate-serviceop good l)))
    ;; bad adapterTier
    (is (seq (kb/validate-serviceop (assoc good :adapterTier "t9-bogus") l)))
    ;; G6 — dryRun must be const true
    (is (seq (kb/validate-serviceop (assoc good :dryRun false) l)))
    ;; missing required key
    (is (seq (kb/validate-serviceop (dissoc good :safety) l)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-karakuri-bridge)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
