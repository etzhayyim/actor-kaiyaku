#!/usr/bin/env bb
;; kaiyaku 解約 — kaiyaku→karakuri ServiceOp handoff (kaiyaku decides, karakuri executes).
(ns kaiyaku.methods.karakuri-bridge
  "karakuri_bridge.cljc — kaiyaku 解約 R1 cross-actor handoff to karakuri 絡繰
  (ADR-2606112201 R1 ⇄ ADR-2606039200).

  The boundary (kaiyaku CLAUDE.md): 'Generic web-service ServiceOp adapters
  (T1/T2 engine, ToS stances) → karakuri (kaiyaku composes; never re-implements)'.
  kaiyaku decides WHAT to sever + carries the cost-of-severance honesty; karakuri
  is the execution substrate. This namespace is the seam: it maps a kaiyaku
  severance plan into a karakuri `com.etzhayyim.karakuri.serviceOp` record and
  proves — against karakuri's OWN lexicon — that the two actors' vocabularies do
  not drift (tier scheme + enum values).

  Mappings (kaiyaku → karakuri):
    tier      T1/T2/T3 → adapterTier t1-official-api / t2-headless-browser / t3-structured-export
    sever     → verb 'delete' + safety 'delete' + destructive true (karakuri's verb enum
              has no 'cancel'; a 解約 IS a delete of the member's own subscription/account)
    dry-run   → dryRun true (karakuri G6 const — matches kaiyaku's dry-run-only)
    member-sig→ mutateGate 'awaiting-member-sig' (G5; never 'authorized' from kaiyaku)
    browser   → tosGate ok / refused-automation-prohibited (G2; a T2 over a non-permitted
              stance never reaches here — plan/select-tier already refused it)

  A T3 plan returns NIL — self-submit is the MEMBER's manual procedure, not a
  karakuri ServiceOp (kaiyaku never fabricates an op the member must do by hand).

  Deterministic; pure builders; file I/O only at the #?(:clj …) lexicon-read edge."
  (:require #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io])))

(def tier->adapter
  "kaiyaku tier → karakuri adapterTier enum value (the cross-actor scheme bridge)."
  {"T1" "t1-official-api"
   "T2" "t2-headless-browser"
   "T3" "t3-structured-export"})

;; ── read karakuri's serviceOp lexicon enums (the drift guard's source) ──────

#?(:clj
   (defn- unblob
     "lex/serviceOp.edn is now Datomic/Datascript tx-data (edn-datomize wrap-map,
     per-file :serviceOp/* namespace on the bare :lexicon/:id/:defs keys);
     non-scalar values (here: :defs) are pr-str blob strings. Parse back to the
     original nested value where possible."
     [v]
     (if (string? v)
       (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
            (catch Exception _ v))
       v)))

#?(:clj
   (defn- reconstitute-entity
     "tx-data [{:db/id -1 :serviceOp/lexicon 1 :serviceOp/id \"...\" :serviceOp/defs
     \"...blob...\"}] -> the original bare {:lexicon 1 :id \"...\" :defs {...}} map
     so get-in-based readers below are unchanged."
     [tx-data]
     (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
           (dissoc (first tx-data) :db/id))))

#?(:clj
   (defn lexicon
     "Read karakuri's serviceOp lexicon EDN (now tx-data shape) → the reconstituted
     bare map, unchanged for callers. File I/O edge."
     [path]
     (reconstitute-entity (edn/read-string (slurp (str path))))))

(defn enum-of
  "Extract a property's :enum set from a parsed serviceOp lexicon."
  [lex prop]
  (set (get-in lex [:defs :main :record :properties prop :enum])))

(defn adapter-enum [lex] (enum-of lex :adapterTier))

;; ── map a kaiyaku plan → a karakuri serviceOp record ────────────────────────

(defn plan->serviceop
  "Map a kaiyaku severance plan (string-key map, plan/build-plan shape) to a
  karakuri serviceOp record. T3 → nil (member-submits; not a karakuri op).
  noun defaults to 'subscription' (override via :noun opt for account 退会)."
  ([plan] (plan->serviceop plan {}))
  ([plan {:keys [noun] :or {noun "subscription"}}]
   (let [tier (get plan "tier")]
     (when (#{"T1" "T2"} tier)
       {:service (get plan "svc")
        :noun noun
        :verb "delete"                 ; 解約 = delete of the member's OWN subscription/account
        :safety "delete"
        :destructive true              ; severance is irreversible
        :adapterTier (tier->adapter tier)
        :dryRun true                   ; G6 — kaiyaku is dry-run-only; karakuri executes post-R1
        :tosGate "ok"                  ; T2 over a non-permitted stance never reaches here (G2)
        :mutateGate "awaiting-member-sig"})))) ; G5 — never 'authorized' from kaiyaku

;; ── validate a produced op against karakuri's lexicon (no-drift proof) ──────

(def ^:private required
  [:service :noun :verb :safety :destructive :adapterTier :dryRun :tosGate :mutateGate])

(defn validate-serviceop
  "Validate a kaiyaku-produced serviceop against karakuri's lexicon enums. Returns
  [error-strings]. Empty = the op is a valid karakuri ServiceOp (cross-actor
  compatible)."
  [op lex]
  (let [errs (transient [])]
    (doseq [k required]
      (when-not (contains? op k) (conj! errs (str "missing " k))))
    (let [checks {:safety :safety :adapterTier :adapterTier
                  :tosGate :tosGate :mutateGate :mutateGate}]
      (doseq [[opk prop] checks]
        (let [allowed (enum-of lex prop)]
          (when (and (contains? op opk) (not (contains? allowed (get op opk))))
            (conj! errs (str opk " " (pr-str (get op opk)) " not in karakuri enum " allowed))))))
    ;; G6 — karakuri's dryRun is const true
    (when (not= true (:dryRun op)) (conj! errs "dryRun must be true (G6 const)"))
    (persistent! errs)))

(defn tier-scheme-aligned?
  "The cross-actor invariant: kaiyaku's three tiers map EXACTLY onto karakuri's
  adapterTier enum (no extra, no missing). If karakuri renames/adds a tier, this
  fails — forcing kaiyaku to reconcile rather than silently drift."
  [lex]
  (= (set (vals tier->adapter)) (adapter-enum lex)))
