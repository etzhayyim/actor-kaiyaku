(ns kaiyaku.catalog
  "kaiyaku 解約 — real-service cancellation-procedure CATALOG for the clj/ lane
  (ADR-2606112201 R1, clj-native sibling of methods/catalog.cljc).

  Loads the SHARED data/cancel-procedures.kotoba.edn — which uses real :proc/*
  keywords, so the clj/ lane reads it directly with clojure.edn (no custom parser;
  the methods/ lane string-keys it for Python parity). Same honesty gates:

    G3 — derive-tier mirrors plan/select-tier EXACTLY; a :prohibited / :unknown
      browser stance never yields T2.
    G8 — every entry carries :proc/notice-days + :proc/penalty-jpy (cost-of-severance).
    G6 — every entry :operator-verified false (:representative; verify before live use).
    N1 — every entry is a SERVICE, never a person."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def evasion-tokens
  #{"captcha-solve" "proxy-rotate" "stealth" "rate-limit-bypass"
    "fingerprint-spoof" "ip-rotate" "anti-bot-bypass"})

(def required-keys
  [:proc/svc-id :proc/name :proc/category :proc/region :proc/cancel
   :proc/notice-days :proc/penalty-jpy :proc/self-submit-steps
   :proc/disclosed-source :proc/operator-verified :proc/sourcing])

(defn load-file* [path] (edn/read-string (slurp (str path))))

(defn by-id [entries] (into {} (map (juxt :proc/svc-id identity)) entries))

(defn derive-tier
  "Safest-first adapter tier — byte-identical logic to plan/select-tier on the
  real-keyword :svc/cancel values."
  [entry]
  (let [c (or (:proc/cancel entry) {})]
    (cond
      (= (:api c) :available)     "T1"
      (= (:browser c) :permitted) "T2"
      :else                       "T3")))

(defn ->svc-node
  "Catalog entry → a :svc/cancel-shaped node so plan/select-tier can run on it
  (the cross-consistency bridge)."
  [entry]
  (let [c (:proc/cancel entry)]
    {:svc/id (:proc/svc-id entry)
     :svc/label (:proc/name entry)
     :svc/notice-days (:proc/notice-days entry)
     :svc/penalty-jpy (:proc/penalty-jpy entry)
     :svc/cancel {:api (:api c) :browser (:browser c) :self-submit (boolean (:self-submit c))}}))

(defn validate-entry [entry]
  (let [errs (transient [])
        sid (:proc/svc-id entry)
        browser (:browser (:proc/cancel entry))]
    (doseq [k required-keys]
      (when-not (contains? entry k) (conj! errs (str sid ": missing " k))))
    (when (and (= "T2" (derive-tier entry)) (not= browser :permitted))
      (conj! errs (str sid ": G3 — T2 over a non-:permitted browser stance " (pr-str browser))))
    (let [txt (str/lower-case (str/join " " (map str (:proc/self-submit-steps entry))))]
      (doseq [tok evasion-tokens]
        (when (str/includes? txt tok)
          (conj! errs (str sid ": G3 — evasion token '" tok "'")))))
    (when-not (number? (:proc/notice-days entry)) (conj! errs (str sid ": G8 notice-days not a number")))
    (when-not (number? (:proc/penalty-jpy entry)) (conj! errs (str sid ": G8 penalty-jpy not a number")))
    (when (not= false (:proc/operator-verified entry)) (conj! errs (str sid ": G6 operator-verified must be false")))
    (when (not= :representative (:proc/sourcing entry)) (conj! errs (str sid ": sourcing must be :representative")))
    (persistent! errs)))

(defn validate [entries]
  (let [errs (vec (mapcat validate-entry entries))]
    {:ok? (empty? errs) :errors errs}))

(defn enrich-plan
  "ADDITIVELY attach the disclosed real procedure to a (real-keyword) plan when its
  :svc has a catalog entry — never mutating an existing plan field. G8 honesty: a
  ledger↔catalog cost-of-severance discrepancy is shown as :g8-drift, never reconciled."
  [plan by-id]
  (let [e (get by-id (:svc plan))]
    (if-not e
      (assoc plan :catalog-coverage false)
      (let [c-notice (:proc/notice-days e)
            c-penalty (:proc/penalty-jpy e)
            drift (cond-> []
                    (not= c-notice (:notice-days plan)) (conj (str "notice-days ledger=" (:notice-days plan) " catalog=" c-notice))
                    (not= c-penalty (:penalty-jpy plan)) (conj (str "penalty-jpy ledger=" (:penalty-jpy plan) " catalog=" c-penalty)))]
        (cond-> (assoc plan
                       :catalog-coverage true
                       :catalog {:tier (derive-tier e)
                                 :self-submit-steps (vec (:proc/self-submit-steps e))
                                 :notice-days c-notice
                                 :penalty-jpy c-penalty
                                 :disclosed-source (:proc/disclosed-source e)
                                 :operator-verified (:proc/operator-verified e)})
          (seq drift) (assoc-in [:catalog :g8-drift] (vec drift)))))))

(defn enrich-plans [plans by-id] (mapv #(enrich-plan % by-id) plans))
