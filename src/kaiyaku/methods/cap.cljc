#!/usr/bin/env bb
;; kaiyaku 解約 — severance CAPABILITY (the revocable leash for live cancel).
(ns kaiyaku.methods.cap
  "cap.cljc — kaiyaku 解約 R1 severance capability (ADR-2606112201 R1, on the
  kotoba CACAO / DelegationChain model verified for ibuki, ADR-2606111400 +
  ADR-2605231525). PRESENT-ONLY: this namespace holds NO signature primitive of
  any kind — kaiyaku presents member-signed bytes, it never signs.

  Why a capability and not a held key: severance is DESTRUCTIVE (closing a
  member's account / cancelling a paid tie). The right credential is neither a
  platform-held key (prohibited — no-server-key, ADR-2605231525) nor a passkey
  touched per-tie (un-ergonomic). It is a SCOPED, EXPIRING, REVOCABLE capability
  the MEMBER signs in their OWN runtime and hands kaiyaku to PRESENT.

  kaiyaku tightens the ibuki leash in one charter-critical way:
    ibuki's capability is a blanket `datom:transact` on a graph. kaiyaku's
    capability additionally carries an `approved` ALLOWLIST of svc-ids — the
    exact set the MEMBER approved at the G5 human-in-the-loop interrupt. The
    leash is bound to WHAT WAS APPROVED, not just to a graph: a tie the member
    did not approve can never be severed even with a valid, unexpired bundle
    (usable? returns false). This makes the member-sig gate (G5) and the
    capability scope ONE artifact.

  How the kotoba CACAO binds (mirrors ibuki delegation.cljc, verified
  2026-06-11):
    - `aud` is the kotoba NODE's operator DID (the audience the capability is
      presented TO; kotoba checks cacao.p.aud == operator_did). NOT kaiyaku's
      DID — kaiyaku is the BEARER that presents the bytes.
    - `write_author = the ISSUING MEMBER`: the severance the driver enacts is
      ATTRIBUTED on-record to the consenting human. Autonomy WITH a named human
      principal (相互監視 / consent), never an anonymous self-acting agent.
    - REVOCATION = consent withdrawn: once `exp` passes, the driver self-disables
      the live path and falls back to dry-run-only. Stop re-issuing → kaiyaku
      quietly stops severing. The leash is LEASED, never owned.

  Deterministic: expiry is checked against a caller-supplied `:now-epoch` (no
  wall clock inside any method — the live boundary supplies it). House style:
  `:…` keyword strings stay strings; pure fns; file I/O only at the #?(:clj …)
  edge. Portable .cljc."
  (:refer-clojure :exclude [load])
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

(def capability
  "The only capability kaiyaku's severance driver ever needs."
  "service:cancel")

(def graph
  "The resource graph this capability is scoped to."
  "graph:kaiyaku")

;; `approved` (the member-approved svc-id allowlist from the G5 interrupt) is
;; what makes kaiyaku's leash tighter than ibuki's blanket datom:transact.
(def required-keys ["cacao_b64" "aud" "capability" "graph" "exp" "nonce" "approved"])

;; ── the refusal marker (cap.CapabilityError) ────────────────────────────────

(defn cap-error
  "A capability bundle is malformed or used outside its scope."
  [msg]
  (ex-info msg {:kaiyaku/cap-error true}))

(defn cap-error? [e]
  (boolean (:kaiyaku/cap-error (ex-data e))))

;; ── load (member-issued bundle sidecar; fail-open when absent) ──────────────

#?(:clj
   (defn load
     "Load a member-issued severance-capability bundle (JSON sidecar). Returns
     nil if absent — the driver then falls back to dry-run-only (fail-open,
     never crashes). Raises cap-error on a present-but-malformed bundle."
     [path]
     (let [f (io/file (str path))]
       (when (.exists f)
         (let [parse (requiring-resolve 'cheshire.core/parse-string)
               bundle (parse (slurp f))
               missing (vec (remove #(contains? bundle %) required-keys))]
           (when (seq missing)
             (throw (cap-error (str "capability bundle missing keys " missing))))
           (when (not= (get bundle "capability") capability)
             (throw (cap-error
                     (str "capability " (pr-str (get bundle "capability"))
                          " != " (pr-str capability)
                          " (kaiyaku's severance driver only ever needs service:cancel)"))))
           (when (not= (get bundle "graph") graph)
             (throw (cap-error
                     (str "capability graph " (pr-str (get bundle "graph"))
                          " != " (pr-str graph)))))
           (when-not (str/starts-with? (str (get bundle "aud" "")) "did:")
             (throw (cap-error
                     (str "capability audience must be a DID (the kotoba node it is "
                          "presented to — kotoba checks cacao.aud == operator_did)"))))
           (when (contains? #{nil ""} (get bundle "nonce"))
             (throw (cap-error "capability must carry a nonce (replay protection)")))
           (when-not (sequential? (get bundle "approved"))
             (throw (cap-error
                     "capability must carry an `approved` svc-id allowlist (the G5 member-approved set)")))
           bundle)))))

;; ── approved? (the member-approved allowlist — the G5 gate, in the leash) ────

(defn approved?
  "Is `svc-id` in this capability's member-approved allowlist? A tie the member
  did not approve is never severable, even under a valid bundle (G5 in the leash)."
  [bundle svc-id]
  (boolean (and bundle (some #{svc-id} (get bundle "approved")))))

;; ── usable? (the leash, checked — pure fn of bundle metadata) ───────────────

(defn- ->long [x]
  (if (number? x)
    (long x)
    #?(:clj (Long/parseLong (str x))
       :default (js/parseInt (str x) 10))))

(defn usable?
  "May kaiyaku present this capability to sever `:svc-id` at `:now-epoch`? Pure
  function of the bundle metadata — the same answer before and after a restart
  (the leash, checked). `:now-epoch` is supplied by the caller (the live
  boundary); no wall clock here. Returns [ok? why]."
  [bundle {:keys [now-epoch svc-id]}]
  (cond
    (nil? bundle)
    [false "no capability (dry-run-only until a member issues one)"]

    (not (approved? bundle svc-id))
    [false (str "svc " (pr-str svc-id) " is not in the member-approved allowlist "
                "(G5: a tie the member did not approve is never severable)")]

    (>= now-epoch (->long (get bundle "exp")))
    [false (str "capability expired (exp " (get bundle "exp") " <= now " now-epoch ") — "
                "consent must be renewed; the driver falls back to dry-run")]

    :else
    [true (str "usable (svc " svc-id ", expires " (get bundle "exp")
               ", aud " (get bundle "aud") ")")]))

;; ── audience (the node, not kaiyaku) ────────────────────────────────────────

(defn audience
  "The DID this capability is presented TO — the kotoba node's operator DID.
  NOT kaiyaku's DID; kaiyaku is the bearer."
  [bundle]
  (get bundle "aud"))

;; ── issuance template (the shape the MEMBER signs — kaiyaku never signs) ─────

(defn issuance-template
  "The CACAO PAYLOAD a member must sign to issue the severance capability —
  emitted for the member's OWN signing runtime (kaiyaku does NOT sign; this is
  just the shape). Mirrors kotoba_auth::CacaoPayload + the ibuki issuance shape:
    - `aud` is the NODE DID (audience), not kaiyaku;
    - `resources` is the SIWE form (`kotoba://can/<cap>` + `kotoba://graph/<cid>`);
    - `approved` is the explicit svc-id allowlist (kaiyaku's tightening — the
      member signs over EXACTLY the ties they approved at the G5 interrupt);
    - write_author resolves to `iss` (the member) — the on-record human principal."
  [{:keys [member-did node-did graph-cid exp-iso nonce-hex approved]}]
  {"iss" member-did
   "aud" node-did
   "exp" exp-iso
   "nonce" nonce-hex
   "version" "1"
   "approved" (vec approved)
   "resources" [(str "kotoba://can/" capability)
                (str "kotoba://graph/" graph-cid)]
   "_note" (str "member signs this with their OWN key (passkey/wallet) in their own runtime; "
                "kaiyaku holds no key and never signs — present-only (ADR-2605231525). "
                "kotoba attributes the severance to iss (the member) — accountability by "
                "consent. `approved` binds the leash to the exact ties the member approved (G5).")})
