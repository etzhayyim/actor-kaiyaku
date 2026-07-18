#!/usr/bin/env bb
;; kaiyaku 解約 — the MEMBER-side tool that mints a revocable severance capability.
(ns kaiyaku.tools.issue-capability
  "issue_capability.cljc — the MEMBER's OWN signing-runtime tool that mints a
  revocable severance capability (ADR-2606112201 R1, ADR-2605231525 §委任).

  THIS IS NOT PART OF THE kaiyaku ACTOR. The actor (`methods/*.cljc`) is
  present-only and NEVER signs (cap.cljc holds no signature primitive). This is
  the human's own runtime: a member runs it on their own machine, with their own
  Ed25519 key, to issue a SCOPED + EXPIRING + svc-ALLOWLISTED capability kaiyaku
  then PRESENTS. It therefore MAY do crypto — the no-server-key rule binds the
  ACTOR, not the member's tool. Written in clj/bb per the repo rule (Ed25519 via
  JDK java.security — no third-party dep).

  What it assembles (the sidecar bundle methods/cap.cljc loads + presents):
    {cacao_b64, aud, capability, graph, exp, nonce, approved}
  where `approved` is the exact svc-id allowlist the member approved at the G5
  interrupt — kaiyaku's tightening over ibuki's blanket leash. The member signs
  the SIWE/CAIP-122 plaintext (`siwe-message`) with their Ed25519 key; the
  signature + payload are packed into `cacao_b64`.

  HONEST SCOPE (read before relying on it live):
    - The DETERMINISTIC scaffold (payload shape, SIWE plaintext, did:key from the
      member pubkey, the sidecar contract) is pure + test-covered, and the
      Ed25519 sign→verify roundtrip is exercised.
    - Producing the EXACT CBOR-CACAO bytes the live kotoba node reconstructs +
      verifying byte-parity against `kotoba-auth` is the G6 OPERATOR step (the
      same honesty as the catalog's operator-verified=false). `cacao_b64` here is
      a canonical-JSON envelope of {p, s}; an operator swaps in the CBOR encoder
      verified against the running node before any live presentation.
    - The member's seed is THEIR secret — never commit it, never hand it to
      kaiyaku. kaiyaku only ever sees `cacao_b64` (opaque) + the sidecar metadata.
      Revoke by letting `exp` pass (stop re-issuing)."
  (:require [clojure.string :as str]
            [kaiyaku.methods.cap :as cap]
            [multiformats.core :as mf]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import [java.security KeyPairGenerator Signature]
                   [java.util Base64])))

;; ── base58btc (did:key) ─────────────────────────────────────────────────────

(defn b58
  "base58btc encode a byte seq (leading-zero bytes → leading '1').
   Delegates to the shared com-junkawasaki/multiformats-clj (portable clj+cljs)."
  [bytes]
  (mf/base58btc bytes))

(defn did-key-from-pubkey
  "Raw 32-byte Ed25519 pubkey → did:key:z6Mk… (multicodec 0xed01 + base58btc,
  multibase 'z'). The 0xed01 prefix over a 32-byte key always yields a z6Mk… id."
  [pub32]
  (str "did:key:z" (b58 (concat [0xed 0x01] pub32))))

;; ── base32lower (CIDv1, no padding) — for the graph resource ─────────────────
;; Delegated to the shared com-junkawasaki/multiformats-clj (byte-identical).

(defn base32-lower
  "RFC4648 base32 lowercase, no padding — delegates to multiformats.core/base32."
  [bytes]
  (mf/base32 bytes))

(defn graph-cid
  "KotobaCid::from_bytes(name) — CIDv1 dag-cbor sha2-256, base32lower 'b' prefix.
  Delegates to multiformats.core/kotoba-cid (byte-identical to the prior helper)."
  [name]
  #?(:clj (mf/kotoba-cid (str name))
     :default (str "b" name)))

;; ── the CACAO payload + SIWE plaintext ──────────────────────────────────────

(defn cacao-payload
  "The CACAO `p` map the member signs — reuses cap/issuance-template (single SoT
  for the issuance shape) so the tool and the verifier never drift."
  [opts]
  (cap/issuance-template opts))

(defn siwe-message
  "The CAIP-122/SIWE plaintext reconstructed + verified server-side. Deterministic."
  [{:strs [iss aud exp nonce version resources]}]
  (str "etzhayyim.com wants you to sign in with your account:\n" iss "\n\n"
       "Issue a kaiyaku 解約 severance capability.\n\n"
       "URI: " aud "\n"
       "Version: " version "\n"
       "Nonce: " nonce "\n"
       "Expiration Time: " exp "\n"
       "Resources:\n"
       (str/join "\n" (map #(str "- " %) resources))))

;; ── Ed25519 (member's own runtime; JDK, no third-party) ──────────────────────

#?(:clj
   (defn gen-keypair
     "Generate a throwaway member Ed25519 keypair. Returns {:private .. :public ..
     :pub32 (raw 32-byte pubkey) :did (did:key)}."
     []
     (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
           ;; raw pubkey = trailing 32 bytes of the X.509 SubjectPublicKeyInfo
           enc (.getEncoded (.getPublic kp))
           pub32 (vec (take-last 32 (seq enc)))]
       {:private (.getPrivate kp) :public (.getPublic kp)
        :pub32 pub32 :did (did-key-from-pubkey pub32)})))

#?(:clj
   (defn sign-b64
     "Ed25519-sign a string with the member's private key → base64 signature."
     [private-key ^String message]
     (let [s (Signature/getInstance "Ed25519")]
       (.initSign s private-key)
       (.update s (.getBytes message "UTF-8"))
       (.encodeToString (Base64/getEncoder) (.sign s)))))

#?(:clj
   (defn verify
     "Verify an Ed25519 base64 signature over message with a public key."
     [public-key ^String message ^String sig-b64]
     (let [v (Signature/getInstance "Ed25519")]
       (.initVerify v public-key)
       (.update v (.getBytes message "UTF-8"))
       (.verify v (.decode (Base64/getDecoder) sig-b64)))))

;; ── assemble the sidecar bundle (the contract methods/cap.cljc loads) ───────

(defn- json-escape [s]
  (str/escape (str s) {\" "\\\"" \\ "\\\\" \newline "\\n"}))

(defn- canonical-json
  "Minimal stable JSON for the {p, s} CACAO envelope (sorted keys)."
  [m]
  (str "{" (str/join ","
                     (for [k (sort (keys m))]
                       (str "\"" (json-escape k) "\":\"" (json-escape (get m k)) "\"")))
       "}"))

(defn sidecar
  "Assemble the {cacao_b64, aud, capability, graph, exp, nonce, approved} sidecar
  bundle — the exact contract methods/cap.cljc/load + usable? consume. `exp` is
  the epoch-seconds integer (cap/->long parses it; the SIWE plaintext carries the
  ISO form). `cacao_b64` packs the signed envelope (see HONEST SCOPE — CBOR
  byte-parity vs the live node is the operator step)."
  [{:keys [node-did graph-name exp-epoch nonce approved siwe-sig payload]}]
  (let [envelope {"p" (str payload) "s" (or siwe-sig "")}
        cacao-b64 #?(:clj (.encodeToString (Base64/getEncoder)
                                           (.getBytes (canonical-json envelope) "UTF-8"))
                     :default (canonical-json envelope))]
    {"cacao_b64" cacao-b64
     "aud" node-did
     "capability" cap/capability
     "graph" cap/graph
     "exp" exp-epoch
     "nonce" nonce
     "approved" (vec approved)
     "_graph_cid" #?(:clj (graph-cid graph-name) :default graph-name)
     "_status" (if siwe-sig "member-signed (CBOR byte-parity = G6 operator step)"
                   "UNSIGNED — member must sign siwe-message in their own runtime")}))

;; ── one-call issuance (deterministic scaffold; signing optional) ────────────

(defn build
  "Build a full issuance: the CACAO payload, the SIWE plaintext, and the sidecar
  bundle. If :private-key is supplied the SIWE plaintext is signed; otherwise the
  sidecar is UNSIGNED (the member signs siwe-message themselves and calls sidecar
  with :siwe-sig). Pure except the optional #?(:clj sign) edge.

  opts: {:member-did :node-did :graph-cid :graph-name :exp-iso :exp-epoch
         :nonce :approved [svc-ids] :private-key (optional)}"
  [{:keys [member-did node-did graph-cid exp-iso exp-epoch nonce approved graph-name private-key]}]
  (let [payload (cacao-payload {:member-did member-did :node-did node-did
                                :graph-cid graph-cid :exp-iso exp-iso
                                :nonce-hex nonce :approved approved})
        siwe (siwe-message payload)
        sig #?(:clj (when private-key (sign-b64 private-key siwe)) :default nil)]
    {:payload payload
     :siwe-message siwe
     :sidecar (sidecar {:node-did node-did :graph-name (or graph-name "kaiyaku")
                        :exp-epoch exp-epoch :nonce nonce :approved approved
                        :siwe-sig sig :payload payload})}))
