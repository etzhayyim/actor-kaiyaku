(ns kaiyaku.vault-ledger
  "復号済み kagitaba 契約 item → 縁-ledger（`kaiyaku.ledger/parse` と同じ
  `{:nodes :edges}` shape）+ 何が足りないかの明細。

  **kaiyaku は vault を開けない。** この ns が受け取るのは既に復号された item の
  vector で、鍵・パスフレーズ・ストア・ネットワークには一切触れない。復号は kagi の
  AccessGovernor を通った呼び出し側（cloud-itonami-app）の責務で、ここに平文が
  渡ってきた時点で既に開示は検閲済み——kaiyaku がその判断をやり直すことはない。

  G1（member-principal）: seed の 9 本は `:synthetic`。ここから来る縁は
  `:sourcing :member-vault`——本人の vault にある本人の契約だけで、他人の縁は入らない。
  N1: 縁の相手は常に SERVICE。item は kagitaba の `Contract` section を持つものだけを
  service として扱い、人・連絡先は構造的に入り得ない。

  ## 埋めない穴を報告する

  `kaiyaku.analyze` の `burden`/`recommend` は欠損値を 0 に丸める
  （`(or (:en/monthly-cost-jpy tie) 0)`）。synthetic seed では全項目が埋まっている
  前提なので妥当だったが、実 vault の契約には **利用実績（usage-score /
  last-used-days）が無い**——kagi は課金の台帳であって利用ログではない。欠損を 0 に
  丸めたまま渡すと「一度も使っていない」と読まれ、`recommend` は `:sever` を返す。
  黙って解約を推奨する縁が生えることになる。

  なので `->ledger` は縁を作ると同時に `:gaps` を返し、`analyzable` は穴のある縁を
  `analyze` に渡さない。**足りないものは推定せず、足りないと言う。**

  ## 通貨

  `analyze` の金額は `:en/monthly-cost-jpy`（JPY 固定）。JPY 以外の契約に
  為替を当てて JPY を作ることはしない——レートは今日の値であって契約の事実ではなく、
  それを台帳に混ぜると「いくら払っているか」が観測日に依存し始める。JPY 以外は
  `:en/amount-minor` / `:en/currency` を実額のまま持ち、`:non-jpy-amount` として
  gap に記録する（表示はできる、JPY 前提の burden 計算だけができない）。"
  (:require [clojure.string :as str]
            [kagitaba.contract :as contract]))

(def analyze-required
  "`kaiyaku.analyze/recommend` が 0 に丸めずに読める必要のある縁の属性
  （順序は出力を決定的にするために固定）。"
  [:en/monthly-cost-jpy :en/usage-score :en/last-used-days])

(defn- slug [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "")))

(defn svc-id
  "縁-ledger 上の service id。kaiyaku catalog の svc-id が記録されていればそれを使い
  （= 解約手順と同じ鍵で引ける）、無ければ title から導出する。

  **title の slug が空になったら item id に落ちる。** 日本語だけの名前
  （「謎のサブスク」等）は ASCII slug が空文字になり、そのまま連結すると
  全部が `svc:` に潰れて別々の契約が 1 本の縁に合流する。"
  [c]
  (let [proc (:contract/cancel-proc-id c)
        s (slug (:contract/title c))]
    (str "svc:" (cond
                  (contract/recorded? proc) proc
                  (not (str/blank? s)) s
                  :else (or (:contract/item-id c) "unidentified")))))

(defn tie-kind
  "縁の種類。課金があるなら :subscribes、無償で持っているだけなら :holds-account、
  契約の実体が分からないまま引き落とされているなら :recurring-charge。"
  [c]
  (let [status (:contract/status c)
        amount (:contract/amount-minor c)
        plan (:contract/plan c)]
    (cond
      (and (not (contract/recorded? plan)) (not (contract/recorded? status))
           (contract/recorded? amount) (pos? amount))
      :recurring-charge

      (contains? #{:cancelled :dormant} status) :holds-account
      (and (contract/recorded? amount) (pos? amount)) :subscribes
      (and (contract/recorded? amount) (zero? amount)) :holds-account
      :else :holds-account)))

(defn- monthly-jpy
  "JPY 建てのときだけ月額を返す。それ以外は nil（為替を当てて作らない）。"
  [c]
  (let [amount (:contract/amount-minor c)
        cur (:contract/currency c)
        per-year (contract/charges-per-year c)]
    (when (and (contract/recorded? amount) (contract/recorded? per-year)
               (= "JPY" cur))
      (/ (* amount per-year) 12))))

(defn ->svc-node
  "契約 → `:svc/*` ノード。解約手順（notice-days / penalty / cancel スタンス）は
  catalog に一致する entry があるときだけ載せる——無い手順を発明しない。"
  [c proc]
  (cond-> {:svc/id (svc-id c)
           :svc/label (:contract/title c)
           :svc/kind (if (= :subscribes (tie-kind c)) :subscription :account)
           :svc/sourcing :member-vault}
    proc (assoc :svc/cancel (:proc/cancel proc)
                :svc/notice-days (:proc/notice-days proc)
                :svc/penalty-jpy (:proc/penalty-jpy proc)
                :svc/proc-id (:proc/svc-id proc)
                :svc/operator-verified (:proc/operator-verified proc))))

(defn ->tie
  "契約 → member→service の縁。持っていない事実のキーは **置かない**
  （nil を置くと analyze が 0 に丸める）。"
  [member-id c]
  (let [amount (:contract/amount-minor c)
        cur (:contract/currency c)
        jpy (monthly-jpy c)]
    (cond-> {:en/from member-id
             :en/to (svc-id c)
             :en/kind (tie-kind c)
             :en/sourcing :member-vault}
      (contract/recorded? amount) (assoc :en/amount-minor amount)
      (contract/recorded? cur) (assoc :en/currency cur)
      (contract/recorded? (:contract/cycle c)) (assoc :en/cycle (:contract/cycle c))
      (contract/recorded? (:contract/status c)) (assoc :en/status (:contract/status c))
      jpy (assoc :en/monthly-cost-jpy jpy))))

(defn gaps
  "この縁で `analyze` を信用できない理由。空なら analyze に渡してよい。"
  [c tie]
  (let [amount (:contract/amount-minor c)
        cur (:contract/currency c)]
    (cond-> (into [] (keep (fn [k] (when-not (contains? tie k) {:missing k})))
                  analyze-required)
      (and (contract/recorded? amount) (contract/recorded? cur) (not= "JPY" cur))
      (conj {:reason :non-jpy-amount :currency cur})

      (seq (contract/problems c))
      (into (map #(assoc % :reason :unparseable-field) (contract/problems c))))))

(defn ->ledger
  "復号済み item 群 → `{:nodes :edges :gaps :contracts}`。

  `procedures` は kaiyaku catalog の `svc-id → entry` map（`catalog/by-id` の出力）。
  `today` は `[y m d]`——この ns も時計を持たない。"
  [{:keys [member-id items procedures today]
    :or {procedures {} member-id "member:self"}}]
  (let [contracts (into [] (comp (filter contract/contract?)
                                 (map #(contract/summary % today)))
                        items)]
    (reduce
     (fn [acc c]
       (let [proc (get procedures (when (contract/recorded? (:contract/cancel-proc-id c))
                                    (:contract/cancel-proc-id c)))
             tie (->tie member-id c)
             g (gaps c tie)]
         (-> acc
             (assoc-in [:nodes (svc-id c)] (->svc-node c proc))
             (update :edges conj tie)
             (cond-> (seq g)
               (update :gaps conj {:svc (svc-id c)
                                   :label (:contract/title c)
                                   :reasons g})))))
     {:nodes {member-id {:member/id member-id :member/sourcing :member-vault}}
      :edges [] :gaps [] :contracts contracts}
     contracts)))

(defn analyzable
  "`kaiyaku.analyze/analyze` にそのまま渡せる部分だけを取り出す。穴のある縁は
  落とす——推奨を出せないことと、解約を推奨することは違う。"
  [{:keys [nodes edges gaps]}]
  (let [blocked (into #{} (map :svc) gaps)]
    {:nodes nodes
     :edges (into [] (remove #(contains? blocked (:en/to %))) edges)}))

(defn procedure-for
  "契約 → 解約手順 entry。catalog に無ければ nil（手順を捏造しない）。"
  [c procedures]
  (let [id (:contract/cancel-proc-id c)]
    (when (contract/recorded? id) (get procedures id))))
