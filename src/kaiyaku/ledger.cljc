(ns kaiyaku.ledger
  "縁-ledger parsing — kaiyaku 解約 (ADR-2606112201), cljc port of methods/analyze.py
  `load`.

  The ledger EDN is a vector of maps: :svc/* nodes (services), :member/* nodes
  (the member), and :en/* edges (the ties). File I/O is a HOST capability —
  this namespace parses a string; the caller slurps/fetches it.

  CONSTITUTIONAL:
    G1 — member-principal, own ties only. The committed seed is fully
      :synthetic; live per-member facts are consent- + G7-gated and encrypted.
    N1 — human relationships are UNREPRESENTABLE here: a tie target is always
      a SERVICE. Any person/contact/relationship node kind throws at parse."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(def person-node-keys
  "N1 — node kinds that may never enter the 縁-ledger."
  #{:person/id :contact/id :relationship/id})

(defn parse
  "EDN ledger string → {:nodes {id node-map} :edges [tie-map …]}."
  [edn-str]
  (reduce
   (fn [acc f]
     (cond
       (not (map? f)) acc

       (some person-node-keys (keys f))
       (throw (ex-info "N1: human-relationship nodes are unrepresentable in the 縁-ledger — a tie target is always a SERVICE (route to kokoro 心)"
                       {:gate :n1 :offending-keys (vec (filter person-node-keys (keys f)))}))

       (:svc/id f)    (assoc-in acc [:nodes (:svc/id f)] f)
       (:member/id f) (assoc-in acc [:nodes (:member/id f)] f)

       (and (:en/from f) (:en/to f))
       (update acc :edges conj f)

       :else acc))
   {:nodes {} :edges []}
   (edn/read-string edn-str)))
