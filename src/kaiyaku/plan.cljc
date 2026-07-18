(ns kaiyaku.plan
  "kaiyaku 解約 — severance-plan builder (cljc port of methods/plan.py,
  ADR-2606112201). Dry-run only at R0.

  Adapter tiers (karakuri ServiceOp tiering, ADR-2606039200):
    T1 official-API cancel      — service publishes a cancellation API
    T2 ToS-permitted browser    — browser-use plan over the MEMBER's OWN
                                  session; :prohibited/:unknown stance refuses
                                  T2 by construction (G3)
    T3 self-submit procedure    — generated checklist / 解約通知文 the member
                                  submits THEMSELVES; always available

  CONSTITUTIONAL:
    G3 — ToS-honest, NO detection-evasion: evasion verbs are structurally
      unrepresentable — make-step throws on them.
    G5/G6 — severance is DESTRUCTIVE: every plan requires member-sig +
      explicit dry-run confirm; execute throws at R0.
    G8 — cost-of-severance honesty: notice period / 違約金 are carried into
      the plan; kaiyaku never plans around a contractual obligation."
  (:require [kaiyaku.analyze :as analyze]))

(def evasion-verbs
  "G3 — unrepresentable."
  #{"captcha-solve" "proxy-rotate" "stealth" "rate-limit-bypass"
    "fingerprint-spoof" "ip-rotate" "anti-bot-bypass"})

(def plannable #{:sever :review-cascade})

(defn make-step
  "The only step constructor. Evasion verbs are unrepresentable (G3)."
  [verb detail]
  (when (evasion-verbs verb)
    (throw (ex-info (str "G3: detection-evasion verb '" verb
                         "' is unrepresentable in kaiyaku")
                    {:gate :g3 :verb verb})))
  {:verb verb :detail detail :mode :dry-run})

(defn select-tier
  "Safest-first adapter routing (karakuri ADR-2606039200 pattern).
  :prohibited / :unknown browser stance refuses T2 by construction (G3)."
  [svc]
  (let [cancel (or (:svc/cancel svc) {})]
    (cond
      (= :available (:api cancel))     "T1"
      (= :permitted (:browser cancel)) "T2"
      :else                            "T3")))

(defn build-plan
  "One severance plan for one tie. Dry-run only; never executes."
  [svc tie]
  (let [rec (:recommendation tie)]
    (when-not (plannable rec)
      (throw (ex-info (str "not plannable: recommendation " rec
                           " (only " (sort plannable) ")")
                      {:recommendation rec})))
    (let [tier  (select-tier svc)
          steps (-> []
                    (into (map #(make-step "rehome-dependency"
                                           (str "move " % " off " (:svc tie)
                                                " (SSO/payment) BEFORE severing"))
                               (:dependents tie)))
                    (conj (case tier
                            "T1" (make-step "api-cancel"
                                            (str "call the published cancellation API of "
                                                 (:svc tie)))
                            "T2" (make-step "browser-cancel"
                                            (str "browser-use plan over the member's OWN session on "
                                                 (:svc tie) " (ToS-permitted surface only)"))
                            (make-step "self-submit"
                                       (str "generate 解約/退会 procedure + notice text for "
                                            (:svc tie)
                                            "; the MEMBER submits it themselves"))))
                    (conj (make-step "export-own-data"
                                     (str "T3 portability export of the member's own data from "
                                          (:svc tie) " before closure")))
                    (conj (make-step "confirm-closure"
                                     "verify the service confirms 解約/退会 (email/record)")))]
      {:svc            (:svc tie)
       :svc-label      (:svc-label tie)
       :tier           tier
       :recommendation rec
       :steps          steps
       ;; G8 cost-of-severance honesty — carried, never planned around
       :notice-days    (get svc :svc/notice-days 0)
       :penalty-jpy    (get svc :svc/penalty-jpy 0)
       ;; G5 destructive gates — required before ANY live execution
       :requires       {:member-sig true :dry-run-confirm true
                        :council-lv6-operator-gate true}
       :mode           :dry-run})))

(defn plans
  ([graph] (plans graph (analyze/analyze graph)))
  ([{:keys [nodes]} readout]
   (->> (:ties readout)
        (filter #(plannable (:recommendation %)))
        (mapv #(build-plan (get nodes (:svc %)) %)))))

(defn execute
  "R0: live execution is Council Lv6+ + operator + member-sig gated (G5/G6)."
  [_plan]
  (throw (ex-info "kaiyaku R0: live severance execution is gated (G5/G6) — dry-run only"
                  {:gate :g5-g6})))
