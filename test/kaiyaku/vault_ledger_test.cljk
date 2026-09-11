(ns kaiyaku.vault-ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [kagitaba.contract :as contract]
            [kagitaba.item :as item]
            [kaiyaku.analyze :as analyze]
            [kaiyaku.catalog :as catalog]
            [kaiyaku.vault-ledger :as vl]))

(def today [2026 7 30])

(def procedures
  (catalog/by-id (catalog/load-file* "data/cancel-procedures.kotoba.edn")))

(defn- item-of [title c]
  (item/item* {:category :membership :title title :id (str "itm-" title)
               :sections [(contract/section c)]}))

(defn- ledger [items]
  (vl/->ledger {:member-id "member:self" :items items
                :procedures procedures :today today}))

;; ── 実 vault の契約を縁にする ───────────────────────────────────────────────

(deftest builds-ties-from-contract-items
  (let [l (ledger [(item-of "Claude Pro" {:plan "Pro" :status :active
                                          :amount-minor 3000 :currency "JPY"
                                          :cycle :monthly :cancel-proc-id "claude-pro"})])
        tie (first (:edges l))]
    (is (= "member:self" (:en/from tie)))
    (is (= "svc:claude-pro" (:en/to tie)) "catalog svc-id を縁の id に使う")
    (is (= :subscribes (:en/kind tie)))
    (is (= :member-vault (:en/sourcing tie)) "synthetic seed と区別できる出自")
    (is (= 3000 (:en/monthly-cost-jpy tie)))))

(deftest non-contract-items-are-not-services
  (let [l (ledger [(item/item* {:category :login :title "GitHub"})
                   (item-of "Claude Pro" {:plan "Pro" :cycle :monthly})])]
    (is (= 1 (count (:edges l))) "契約 section を持たない item は縁にならない")
    (is (= 1 (count (:contracts l))))))

(deftest catalog-procedure-is-joined-when-known
  (let [l (ledger [(item-of "X Premium" {:plan "Premium" :status :active
                                         :cancel-proc-id "x-premium"})])
        node (get (:nodes l) "svc:x-premium")]
    (is (= 1 (:svc/notice-days node)) "X Premium の 24 時間ガイダンスが縁に載る")
    (is (= false (:svc/operator-verified node))
        "手順は未検証のまま伝わる — 検証済みのふりをしない")))

(deftest unknown-service-gets-no-invented-procedure
  (let [l (ledger [(item-of "謎のサブスク" {:amount-minor 550 :currency "JPY"
                                          :cycle :monthly})])
        node (get (:nodes l) (vl/svc-id (first (:contracts l))))]
    (is (nil? (:svc/notice-days node)) "catalog に無い手順は載せない")
    (is (nil? (:svc/cancel node)))))

(deftest non-ascii-titles-do-not-collide
  (testing "ASCII slug が空になる名前でも別々の縁として残る"
    (let [l (ledger [(item-of "謎のサブスク" {:amount-minor 550})
                     (item-of "別の謎" {:amount-minor 300})])]
      (is (= 3 (count (:nodes l))) "member 1 + service 2")
      (is (not= (:en/to (first (:edges l))) (:en/to (second (:edges l))))
          "2 つの契約が同じ svc-id に潰れてはならない"))))

;; ── 埋めない穴を報告する ────────────────────────────────────────────────────

(deftest usage-is-unknown-so-the-tie-is-not-analyzable
  (let [l (ledger [(item-of "Claude Pro" {:plan "Pro" :status :active
                                          :amount-minor 3000 :currency "JPY"
                                          :cycle :monthly :cancel-proc-id "claude-pro"})])
        tie (first (:edges l))]
    (testing "利用実績を 0 として置かない"
      (is (not (contains? tie :en/usage-score)))
      (is (not (contains? tie :en/last-used-days))))
    (testing "穴として名指しで報告される"
      (is (= #{:en/usage-score :en/last-used-days}
             (into #{} (keep :missing) (:reasons (first (:gaps l)))))))
    (testing "穴のある縁は analyze に渡らない"
      (is (empty? (:edges (vl/analyzable l)))))))

(deftest zeroed-usage-would-have-recommended-severance
  (testing "この ns が存在する理由: 欠損を 0 に丸めると analyze は解約を勧める"
    (let [naive {:nodes {"svc:claude-pro" {:svc/id "svc:claude-pro"}}
                 :edges [{:en/from "member:self" :en/to "svc:claude-pro"
                          :en/kind :subscribes :en/monthly-cost-jpy 3000
                          :en/usage-score 0 :en/last-used-days 0}]}
          rec (:recommendation (first (:ties (analyze/analyze naive))))]
      (is (= :sever rec)
          "使用実績 0 は「一度も使っていない」と読まれる — 未記録をここへ流してはならない"))))

(deftest non-jpy-amount-is-kept-but-not-converted
  (let [l (ledger [(item-of "ChatGPT Plus" {:status :active :amount-minor 2000
                                            :currency "USD" :cycle :monthly
                                            :cancel-proc-id "chatgpt-plus"})])
        tie (first (:edges l))]
    (is (= 2000 (:en/amount-minor tie)) "実額は実額のまま持つ")
    (is (= "USD" (:en/currency tie)))
    (is (not (contains? tie :en/monthly-cost-jpy))
        "USD 20 を ¥20 にしない。為替を当てて JPY を作りもしない")
    (is (some #(= :non-jpy-amount (:reason %)) (:reasons (first (:gaps l))))
        "JPY 前提の burden 計算ができないことを報告する")))

(deftest unparseable-fields-surface-as-gaps
  (let [l (ledger [(item-of "壊れた契約" {:amount-minor "だいたい" :cycle :monthly
                                       :currency "JPY"})])]
    (is (some #(= :unparseable-field (:reason %)) (:reasons (first (:gaps l)))))))

(deftest fully-recorded-tie-is-analyzable
  (testing "利用実績が別経路で入れば、その縁は analyze に渡る"
    (let [l (ledger [(item-of "Claude Pro" {:plan "Pro" :status :active
                                            :amount-minor 3000 :currency "JPY"
                                            :cycle :monthly})])
          enriched (update l :edges
                           (fn [es] (mapv #(assoc % :en/usage-score 90
                                                  :en/last-used-days 1) es)))
          l' (assoc enriched :gaps [])]
      (is (= 1 (count (:edges (vl/analyzable l')))))
      (is (= :keep (:recommendation (first (:ties (analyze/analyze (vl/analyzable l'))))))
          "よく使われている有料契約は保つ"))))

;; ── 縁の種類 ────────────────────────────────────────────────────────────────

(deftest tie-kinds
  (let [k #(vl/tie-kind (contract/read-contract (item-of "s" %)))]
    (is (= :subscribes (k {:plan "Pro" :amount-minor 1000})))
    (is (= :holds-account (k {:plan "Free" :amount-minor 0})))
    (is (= :holds-account (k {:plan "Pro" :status :cancelled :amount-minor 1000})))
    (is (= :recurring-charge (k {:amount-minor 550}))
        "プランも状態も分からないのに引き落とされている = 不明な継続課金")))

;; ── catalog ─────────────────────────────────────────────────────────────────

(deftest catalog-still-valid-with-new-ai-entries
  (let [entries (catalog/load-file* "data/cancel-procedures.kotoba.edn")
        v (catalog/validate entries)]
    (is (:ok? v) (str "catalog errors: " (:errors v)))
    (is (= #{"chatgpt-plus" "claude-pro" "supergrok" "x-premium"}
           (into #{} (comp (filter #(= :ai (:proc/category %))) (map :proc/svc-id)) entries))
        "AI サブスクは 4 件（SuperGrok と X Premium は請求経路が別なので別 entry）")
    (is (= "T3" (catalog/derive-tier (get (catalog/by-id entries) "supergrok")))
        "browser :prohibited は T2 に上がらない（G3）")))

(deftest catalog-is-reachable-from-the-classpath
  (testing "git dep として使う consumer も同じカタログを読む（vendor copy を作らせない）"
    (let [from-path (catalog/load-file* "data/cancel-procedures.kotoba.edn")
          from-cp (catalog/load-catalog)]
      (is (= from-path from-cp)))))

;; ── 課金と契約の突き合わせ ──────────────────────────────────────────────────

(defn- contract-of [title m]
  (contract/summary (item-of title m) today))

(defn- charge [merchant amount cur months]
  (vl/->charge {":handoff/source" ":meisai" ":handoff/merchant" merchant
                ":handoff/typical-amount" amount ":handoff/currency" cur
                ":handoff/months" months ":handoff/occurrences" (count months)
                ":handoff/amount-stable" true}))

(deftest merchant-name-is-not-the-service-name
  (testing "推測で寄せない — ANTHROPIC と Claude Pro は一致しない"
    (let [r (vl/reconcile {:contracts [(contract-of "Claude Pro" {:plan "Pro"})]
                           :charges [(charge "ANTHROPIC" 3000 :jpy ["2026-06" "2026-07"])]})]
      (is (empty? (:matched r)))
      (is (= :no-matching-contract (:charge/note (first (:unmatched-charges r)))))
      (is (= :no-merchant-descriptor-recorded (:note (first (:unmatched-contracts r))))
          "紐付かない理由が「descriptor を書いていない」と名指しされる")))
  (testing "descriptor を書き写してあれば一致する"
    (let [r (vl/reconcile {:contracts [(contract-of "Claude Pro"
                                                    {:plan "Pro" :merchant-descriptor "ANTHROPIC"})]
                           :charges [(charge "ANTHROPIC" 3000 :jpy ["2026-06" "2026-07"])]})]
      (is (= 1 (count (:matched r))))
      (is (empty? (:unmatched-charges r)))
      (is (empty? (:unmatched-contracts r))))))

(deftest matching-is-exact-after-normalizing-case-and-space
  (let [r (vl/reconcile {:contracts [(contract-of "X" {:merchant-descriptor "supergrok  xai"})]
                         :charges [(charge "SUPERGROK XAI" 4400 :jpy ["2026-07"])]})]
    (is (= 1 (count (:matched r))) "大文字小文字と連続空白だけは吸収する"))
  (testing "部分一致では紐付けない"
    (let [r (vl/reconcile {:contracts [(contract-of "X" {:merchant-descriptor "ANTHROPIC"})]
                           :charges [(charge "ANTHROPIC PBC TOKYO" 3000 :jpy ["2026-07"])]})]
      (is (empty? (:matched r))
          "別加盟店かもしれない — 間違った契約に金額を入れるより紐付けない"))))

(deftest a-charge_matching_two_contracts_picks_neither
  (let [r (vl/reconcile
           {:contracts [(contract-of "Claude Pro" {:merchant-descriptor "ANTHROPIC"})
                        (contract-of "Claude Team" {:merchant-descriptor "anthropic"})]
            :charges [(charge "ANTHROPIC" 3000 :jpy ["2026-07"])]})]
    (is (empty? (:matched r)))
    (is (= 1 (count (:ambiguous r))))
    (is (= 2 (count (:candidates (first (:ambiguous r))))))
    (is (= :same-descriptor-on-several-contracts (:note (first (:ambiguous r)))))))

(deftest a-matched-charge-is-evidence-for-a-missing-amount
  (let [r (vl/reconcile {:contracts [(contract-of "Claude Pro"
                                                  {:plan "Pro" :merchant-descriptor "ANTHROPIC"})]
                         :charges [(charge "ANTHROPIC" 3000 :jpy ["2026-05" "2026-06" "2026-07"])]})
        e (:evidence (first (:matched r)))]
    (is (= {:contract/amount-minor 3000 :contract/currency "JPY"} (:fills e))
        "契約に金額が無く課金にある = 埋められる証拠")
    (is (= "2026-07" (:last-charged-month e)))
    (is (nil? (:conflict e)))))

(deftest a-disagreement-is-reported-not-applied
  (let [r (vl/reconcile {:contracts [(contract-of "Claude Pro"
                                                  {:amount-minor 3000 :currency "JPY"
                                                   :merchant-descriptor "ANTHROPIC"})]
                         :charges [(charge "ANTHROPIC" 3500 :jpy ["2026-07"])]})
        e (:evidence (first (:matched r)))]
    (is (nil? (:fills e)) "記録済みの金額を課金で上書きしない")
    (is (= {:field :contract/amount-minor :recorded 3000 :charged 3500} (:conflict e))
        "値上げかもしれないし照合ミスかもしれない — どちらも人が見る")))

(deftest currency-disagreement-is-never-converted
  (let [r (vl/reconcile {:contracts [(contract-of "ChatGPT Plus"
                                                  {:amount-minor 2000 :currency "USD"
                                                   :merchant-descriptor "OPENAI"})]
                         :charges [(charge "OPENAI" 3000 :jpy ["2026-07"])]})
        e (:evidence (first (:matched r)))]
    (is (= {:recorded "USD" :charged "JPY"} (:currency-conflict e)))))

(deftest a-cancelled-contract-still-being-charged-is-surfaced
  (let [r (vl/reconcile {:contracts [(contract-of "解約したはず"
                                                  {:status :cancelled :merchant-descriptor "GHOST"})]
                         :charges [(charge "GHOST" 980 :jpy ["2026-06" "2026-07"])]})
        e (:evidence (first (:matched r)))]
    (is (true? (:charged-after-cancellation e))
        "解約済みと記録した契約に課金が続いている")))

(deftest a-contract-with-no-charge-is-its-own-finding
  (let [r (vl/reconcile {:contracts [(contract-of "App Store 経由"
                                                  {:merchant-descriptor "APPLE.COM/BILL"})]
                         :charges [(charge "ANTHROPIC" 3000 :jpy ["2026-07"])]})]
    (is (= 1 (count (:unmatched-contracts r))))
    (is (= :descriptor-recorded-but-no-charge-seen (:note (first (:unmatched-contracts r))))
        "書き写してあるのに課金が無い = 解約済みか別カードか、確かめる価値がある")))

(deftest reconcile-writes-nothing
  (let [c (contract-of "Claude Pro" {:merchant-descriptor "ANTHROPIC"})
        before (pr-str c)]
    (vl/reconcile {:contracts [c] :charges [(charge "ANTHROPIC" 3000 :jpy ["2026-07"])]})
    (is (= before (pr-str c)) "所見を返すだけで、契約は書き換えない")))
