#!/usr/bin/env bb
;; kaiyaku 解約 — catalog + authorization-RECEIPT Datom emit / persistence (G9 audit).
(ns kaiyaku.methods.receipt
  "receipt.cljc — kaiyaku 解約 R1 audit-trail persistence (ADR-2606112201 R1,
  on the kotoba commit-DAG of methods/kotoba.cljc).

  Two GROUND datom emitters, both persisted to the same append-only, content-
  addressed, tamper-evident (verify-chain) kotoba log the 縁-ledger uses:

    1. catalog-datoms — the real-service cancellation-procedure catalog
       (:proc/* nodes) becomes durable state, so the disclosed procedure a plan
       cited is itself recorded (not just referenced at runtime).

    2. receipt-datoms — every driver AUTHORIZATION descriptor becomes a
       :kaiyaku.receipt/* audit datom: WHAT kaiyaku authorized/refused, for which
       svc, at which tier, authorized? / executed (always false at R1). This is
       the G9 audit answer — the member can query exactly what kaiyaku touched.

  SAFETY (enforced by emit + tests):
    G3/no-server-key — a receipt NEVER stores a credential: no `cacao_b64`, no
      signature, no capability secret. It records authorized-by=member /
      server-signed=false and the OUTCOME only (no-secrets test-enforced).
    G2/N1 — receipts are keyed on the SERVICE + tx (an action record), never a
      per-member score and never a person.
    G6 — receipt :executed is always false at R1 (the driver authorizes, a
      post-R1 component executes); a receipt can never claim a live cancellation.

  Deterministic: caller supplies tx-id + as-of (no wall clock). Pure datom
  builders; file I/O only at the #?(:clj …) persist edge. Portable .cljc."
  (:require [clojure.string :as str]
            [kaiyaku.methods.kotoba :as k]
            [kaiyaku.methods.catalog :as catalog]))

(defn- kw->s
  "A real keyword → its ':name' string (the methods/ EDN house form); pass other
  values through. Keeps emitted datom values valid for kotoba's edn-val."
  [v]
  (if (keyword? v) (str v) v))

;; ── catalog → GROUND datoms (:proc/*) ───────────────────────────────────────

(defn catalog-datoms
  "Durable EAVT facts for the cancellation-procedure catalog. Steps are recorded
  as a COUNT (not the verbose text) to keep the log compact; the full text lives
  in the catalog EDN. entity id = 'proc:<svc-id>'."
  [entries]
  (vec
   (mapcat
    (fn [e]
      (let [eid (str "proc:" (:proc/svc-id e))
            c (:proc/cancel e)]
        [(k/add eid ":proc/name" (:proc/name e))
         (k/add eid ":proc/category" (kw->s (:proc/category e)))
         (k/add eid ":proc/region" (kw->s (:proc/region e)))
         (k/add eid ":proc/tier" (catalog/derive-tier e))
         (k/add eid ":proc/notice-days" (:proc/notice-days e))
         (k/add eid ":proc/penalty-jpy" (:proc/penalty-jpy e))
         (k/add eid ":proc/cancel.api" (kw->s (:api c)))
         (k/add eid ":proc/cancel.browser" (kw->s (:browser c)))
         (k/add eid ":proc/step-count" (count (:proc/self-submit-steps e)))
         (k/add eid ":proc/disclosed-source" (:proc/disclosed-source e))
         (k/add eid ":proc/operator-verified" (boolean (:proc/operator-verified e)))]))
    entries)))

;; ── authorization descriptor → RECEIPT datoms (:kaiyaku.receipt/*) ──────────

(def ^:private secret-tokens
  "Unambiguous credential markers that must NEVER appear in a receipt value
  (no-server-key). Kept PRECISE — short ambiguous substrings like 'seed' / 'key'
  are NOT here, because they false-positive on benign content (an as-of stamp, a
  service named 'Keychain'). The real threat — a stored capability/signature — is
  a long opaque token, caught by the base64 heuristic below."
  #{"cacao_b64" "cacao_" "private_key" "privatekey" "secret_key" "secretkey"
    "-----begin" "ed25519_seed"})

(defn- assert-no-secret!
  "A receipt value must not be (or contain) a credential. Defence in depth — the
  descriptor doesn't carry one, but a future field must not smuggle one in. Flags
  an unambiguous credential token OR a long base64-ish opaque blob (a signature /
  CACAO bytes), while letting benign short text through."
  [v]
  (let [s (str/lower-case (str v))]
    (when (or (some #(str/includes? s %) secret-tokens)
              (re-find #"[A-Za-z0-9+/]{40,}={0,2}" (str v)))   ; long opaque base64 (sig/CACAO)
      (throw (ex-info (str "G3/no-server-key: a receipt value looks credential-shaped: " (pr-str v))
                      {:gate :G3 :value v}))))
  v)

(defn receipt-datoms
  "Audit datoms for a batch of driver authorization descriptors. as-of is the
  caller-supplied tx time. entity id = 'receipt:<svc>:<as-of>'."
  [descriptors as-of]
  (vec
   (mapcat
    (fn [d]
      (let [svc (get d "svc")
            eid (str "receipt:" svc ":" as-of)]
        (->> [(k/add eid ":kaiyaku.receipt/svc" svc)
              (k/add eid ":kaiyaku.receipt/as-of" (str as-of))
              (k/add eid ":kaiyaku.receipt/authorized" (boolean (get d "authorized")))
              (k/add eid ":kaiyaku.receipt/status" (get d "status"))
              ;; G6 — a receipt can never claim a live cancellation
              (k/add eid ":kaiyaku.receipt/executed" false)
              ;; G3 — provenance, never a credential
              (k/add eid ":kaiyaku.receipt/authorized-by" (or (get d "authorized_by") "n/a"))
              (k/add eid ":kaiyaku.receipt/server-signed" false)]
             ;; defence in depth: refuse to emit a credential-shaped value
             (map (fn [[op e a v]] [op e a (assert-no-secret! v)]))
             vec)))
    descriptors)))

;; ── persistence (commit-DAG; file I/O edge) ─────────────────────────────────

#?(:clj
   (defn persist-catalog!
     "Append the catalog GROUND datoms as ONE content-addressed tx to the log.
     Returns the tx cid."
     [entries log-path {:keys [tx-id as-of prev-cid] :or {prev-cid ""}}]
     (k/append-tx (k/make-tx (catalog-datoms entries) tx-id as-of prev-cid) log-path)))

#?(:clj
   (defn persist-receipts!
     "Append the authorization-receipt datoms as ONE content-addressed tx.
     Returns the tx cid."
     [descriptors log-path {:keys [tx-id as-of prev-cid] :or {prev-cid ""}}]
     (k/append-tx (k/make-tx (receipt-datoms descriptors as-of) tx-id as-of prev-cid) log-path)))

#?(:clj
   (defn catalog-beat
     "IDEMPOTENT-BY-CONTENT catalog heartbeat (the autorun.cljc/beat pattern,
     ADR-2605262130 commit-DAG). Persists the catalog GROUND datoms to a DEDICATED
     catalog log; if the datoms equal the last beat's, the beat is a NO-OP (nothing
     appended). Deterministic (caller supplies tx-id + as-of) → resume-safe: a
     crash mid-beat re-runs to a byte-identical head. Use a catalog-only log-path
     (a mixed log would break the last-tx comparison).

     Returns {:head <cid> :count <n> :appended <bool> :reason <kw|nil>}."
     [entries log-path {:keys [tx-id as-of]}]
     (let [ds (catalog-datoms entries)
           prev (k/head-cid log-path)
           last-ds (let [txs (k/read-log log-path)]
                     (when (seq txs) (get (last txs) ":tx/datoms")))
           base {:count (count ds)}]
       (if (= ds last-ds)
         (assoc base :head prev :appended false :reason :no-change)
         (let [head (k/append-tx (k/make-tx ds tx-id as-of prev) log-path)]
           (assoc base :head head :appended true :reason nil))))))
