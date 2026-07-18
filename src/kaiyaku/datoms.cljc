(ns kaiyaku.datoms
  "kaiyaku 解約 — kotoba Datom-log emitter (cljc port of methods/datom_emit.py,
  canonical EAVT state per ADR-2605312345).

  Datoms are emitted DATA-FIRST as [e a v tx op] vectors (the Python port
  rendered strings directly); render-edn serializes them to kotoba EDN text.
  Two strata:

    GROUND  (durable, op :add) — :svc/* / :member/* nodes and :en/* ties.
    DERIVED (:bond/is-transient true) — burden / recommendation / plan-tier
      readouts. Per G2 these are computed on READ and never stored as ground
      state, so a stale \"this tie is severable\" verdict can never outlive
      the facts beneath it.

  Divergence from the Python emitter: nested :svc/cancel keys render as
  :svc/cancel.api (valid keyword) instead of Python's concatenated
  \":svc/cancel:api\" (not a valid EDN keyword)."
  (:require [clojure.string :as str]
            [kaiyaku.plan :as plan]))

(def node-attrs [:svc/label :svc/kind :svc/category :svc/sourcing
                 :svc/notice-days :svc/penalty-jpy
                 :member/label :member/sourcing])
(def edge-attrs [:en/from :en/to :en/kind :en/monthly-cost-jpy
                 :en/usage-score :en/last-used-days :en/first-seen
                 :en/dep :en/sourcing])

(defn- pad3 [n]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- 3 (count s))) "0")) s)))

(defn ground-datoms
  "Durable EAVT facts for the nodes + ties."
  [nodes edges tx]
  (vec
   (concat
    (for [nid (sort (keys nodes))
          :let [n (get nodes nid)]
          [a v] (concat
                 (for [a node-attrs
                       :let [v (get n a ::absent)]
                       :when (and (not= v ::absent) (not (map? v)))]
                   [a v])
                 (when-let [c (:svc/cancel n)]
                   (for [k (sort (keys c))]
                     [(keyword "svc" (str "cancel." (name k))) (get c k)])))]
      [nid a v tx :add])
    (apply concat
           (map-indexed
            (fn [i e]
              (let [eid (str "en:" (pad3 i))]
                (for [a edge-attrs
                      :let [v (get e a ::absent)]
                      :when (not= v ::absent)]
                  [eid a v tx :add])))
            edges)))))

(defn derived-datoms
  "Transient readout facts — computed on read, never ground state (G2)."
  [nodes readout tx]
  (vec
   (mapcat (fn [t]
             (let [eid (str "readout:" (:svc t))]
               [[eid :bond/is-transient true tx :add]
                [eid :enkiri/burden (:burden t) tx :add]
                [eid :enkiri/recommendation (:recommendation t) tx :add]
                [eid :enkiri/plan-tier (plan/select-tier (get nodes (:svc t))) tx :add]]))
           (:ties readout))))

(defn datoms
  "Full projection: GROUND + DERIVED [e a v tx op] vectors."
  ([graph readout] (datoms graph readout {:tx 1}))
  ([{:keys [nodes edges]} readout {:keys [tx] :or {tx 1}}]
   (into (ground-datoms nodes edges tx)
         (derived-datoms nodes readout tx))))

(defn render-edn
  "Datom vectors → kotoba EDN text (one [e a v tx op] per line)."
  [ds readout]
  (str ";; kaiyaku 解約 — GENERATED kotoba Datom log (ADR-2606112201). DO NOT hand-edit.\n"
       ";; Canonical EAVT state (ADR-2605312345). [e a v tx op].\n"
       ";; GROUND op :add = durable. DERIVED :bond/is-transient = computed on read (G2).\n"
       (str/join "\n" (map pr-str ds))
       "\n;; ties=" (count (:ties readout))
       " recoverable-jpy-mo=" (:recoverable-monthly-jpy readout)
       "\n"))
