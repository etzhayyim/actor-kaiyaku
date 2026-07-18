(ns kaiyaku.tests.test-meisai-ingest
  "kaiyaku 解約 — meisai 明細 handoff ingest tests (closes the meisai → kaiyaku round-trip).

  A SYNTHETIC meisai recurring-charge handoff (fictional merchants) is ingested into 縁-ledger
  forms and fed through kaiyaku's own analyze — proving the wire format round-trips and that a
  recurring card charge lands as a `:recurring-charge` tie kaiyaku decides on (no new decision
  logic). N1: the produced node is always a SERVICE (`:card-merchant`), never a person."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kaiyaku.methods.meisai-ingest :as mi]
            [kaiyaku.methods.analyze :as analyze]))

;; meisai/recurring.cljc handoff shape (real-keyword EDN; read-edn keeps ':…' as strings).
;; Fictional merchants only (G1 synthetic). Includes a tate record + a non-recurring meisai
;; record to prove the source/recurring filter.
(def handoff-edn
  "[{:handoff/source :meisai :handoff/svc \"FICTIONAL STREAM CO\" :handoff/merchant \"FICTIONAL STREAM CO\"
     :handoff/recurring true :handoff/months [\"2026-03\" \"2026-04\" \"2026-05\"] :handoff/occurrences 3
     :handoff/typical-amount 1490 :handoff/currency :jpy :handoff/amount-stable true
     :handoff/action :review :handoff/advisory true}
    {:handoff/source :meisai :handoff/svc \"FICTIONAL USD SAAS\" :handoff/merchant \"FICTIONAL USD SAAS\"
     :handoff/recurring true :handoff/months [\"2026-04\" \"2026-05\"] :handoff/occurrences 2
     :handoff/typical-amount 999 :handoff/currency :usd :handoff/amount-stable true
     :handoff/action :review :handoff/advisory true}
    {:handoff/source :tate :handoff/clause \"auto-renew\" :handoff/action :calendar-notice-window}
    {:handoff/source :meisai :handoff/svc \"ONE OFF\" :handoff/recurring false}]")

(deftest test-source-and-recurring-filter
  (let [cs (mi/ingest handoff-edn)]
    (is (= 2 (count cs)) "only meisai recurring records are ingested (tate + one-off dropped)")
    (is (every? #(= ":meisai" (get % ":handoff/source")) cs))))

(deftest test-candidate-maps-to-recurring-charge-tie
  (let [forms (mi/to-ledger-forms (mi/ingest handoff-edn) "member:self")
        svcs (filter #(contains? % ":svc/id") forms)
        ens (filter #(contains? % ":en/from") forms)]
    (is (= 2 (count svcs)) "one svc node per merchant")
    (is (= 2 (count ens)) "one tie per merchant")
    (is (every? #(= ":card-merchant" (get % ":svc/kind")) svcs) "N1: target is a SERVICE, not a person")
    (is (every? #(= ":recurring-charge" (get % ":en/kind")) ens) "tie is the recurring-charge kind")
    (let [jpy (first (filter #(= "member:self" (get % ":en/from"))
                             (filter #(= 1490 (get % ":en/meisai-typical-amount")) ens)))]
      (is (= 1490 (get jpy ":en/monthly-cost-jpy")) "JPY amount (minor units == yen) becomes the JPY cost"))
    (let [usd (first (filter #(= ":usd" (get % ":en/meisai-currency")) ens))]
      (is (= 0 (get usd ":en/monthly-cost-jpy")) "non-JPY charge lands cost 0 (no FX) → analyze :review"))))

(deftest test-roundtrip-through-analyze
  ;; the proof the round-trip closes: meisai handoff → forms → kaiyaku analyze decides
  (let [forms (mi/to-ledger-forms (mi/ingest handoff-edn) "member:self")
        member {":member/id" "member:self" ":member/label" "Self"}
        {:keys [nodes edges]} (analyze/load-graph (cons member forms))
        res (analyze/analyze nodes edges)
        by-label (into {} (map (juxt #(get % "svc_label") identity) (get res "ties")))]
    (is (= 2 (count (get res "ties"))) "both recurring charges become ties")
    (is (= ":sever" (get (by-label "FICTIONAL STREAM CO") "recommendation"))
        "a recognized JPY recurring charge (usage 0, cost>0) → kaiyaku :sever")
    (is (= ":review" (get (by-label "FICTIONAL USD SAAS") "recommendation"))
        "an un-priced foreign recurring charge → kaiyaku :review, never auto-:sever")
    (is (every? #(= ":recurring-charge" (get % "kind")) (get res "ties")))))

(deftest test-edn-roundtrips
  (let [forms (mi/to-ledger-forms (mi/ingest handoff-edn) "member:self")
        reparsed (analyze/read-edn (mi/forms->edn forms))]
    (is (= forms reparsed) "forms->edn re-reads byte-equivalent via kaiyaku's own EDN reader")))

(deftest test-svc-id-deterministic
  (is (= (mi/svc-id "FICTIONAL STREAM CO") (mi/svc-id "FICTIONAL STREAM CO")) "stable id → idempotent merge")
  (is (str/starts-with? (mi/svc-id "FICTIONAL STREAM CO") "svc:meisai:") "namespaced under meisai")
  (is (not= (mi/svc-id "A") (mi/svc-id "B")) "distinct merchants → distinct ids"))

(deftest test-fx-priced-foreign-charge
  ;; a foreign charge meisai priced via its report-time FX leg (:handoff/jpy-equivalent) lands
  ;; with that JPY cost → analyze can recommend on it (not forced to :review).
  (let [edn "[{:handoff/source :meisai :handoff/svc \"FX SAAS\" :handoff/merchant \"FX SAAS\"
               :handoff/recurring true :handoff/months [\"2026-04\" \"2026-05\"] :handoff/occurrences 2
               :handoff/typical-amount 999 :handoff/currency :usd :handoff/jpy-equivalent 1499
               :handoff/fx-advisory true :handoff/action :review :handoff/advisory true}]"
        forms (mi/to-ledger-forms (mi/ingest edn) "member:self")
        en (first (filter #(contains? % ":en/from") forms))
        member {":member/id" "member:self"}
        {:keys [nodes edges]} (analyze/load-graph (cons member forms))
        res (analyze/analyze nodes edges)
        tie (first (get res "ties"))]
    (is (= 1499 (get en ":en/monthly-cost-jpy")) "FX JPY-equivalent becomes the tie's JPY cost")
    (is (= true (get en ":en/meisai-fx-priced")) "provenance: priced via FX, not native JPY")
    (is (= ":sever" (get tie "recommendation")) "a priced foreign recurring charge → kaiyaku decides :sever")))

(deftest test-no-person-node
  (let [forms (mi/to-ledger-forms (mi/ingest handoff-edn) "member:self")]
    (is (not-any? #(or (contains? % ":person/id") (= ":person" (get % ":svc/kind"))) forms)
        "N1: never a person node")))

#?(:clj (defn -main [& _] (run-tests 'kaiyaku.tests.test-meisai-ingest)))
