(ns kaiyaku.cap
  "kaiyaku 解約 — severance CAPABILITY for the clj/ langgraph lane (ADR-2606112201 R1,
  clj-native sibling of methods/cap.cljc).

  The revocable kotoba CACAO leash, present-only: kaiyaku PRESENTS a member-signed
  bundle, it never signs (no-server-key, ADR-2605231525). The clj/ lane's data model
  is real keywords, so the bundle is keyword-keyed here (the methods/ lane uses the
  string-keyed JSON wire form). Semantics are identical:

    - `:approved` is the member-approved svc-id allowlist (G5 in the leash — a tie the
      member did not approve is never severable, even under a valid bundle);
    - `:aud` is the kotoba NODE DID (the audience the capability is presented TO);
    - expiry is checked against a caller-supplied `:now-epoch` (no wall clock).

  In the clj/ lane the member-sig ALSO surfaces as the langgraph interrupt before
  :approve; the capability is the cryptographic leash that additionally gates the
  (post-R1) live path. Pure fns; no signature primitive here (present-only)."
  (:require [clojure.string :as str]))

(def capability "service:cancel")
(def graph "graph:kaiyaku")

(defn cap-error [msg] (ex-info msg {:kaiyaku/cap-error true}))
(defn cap-error? [e] (boolean (:kaiyaku/cap-error (ex-data e))))

(defn approved?
  "Is `svc-id` in this capability's member-approved allowlist?"
  [bundle svc-id]
  (boolean (and bundle (some #{svc-id} (:approved bundle)))))

(defn- ->long [x] (if (number? x) (long x) (Long/parseLong (str x))))

(defn usable?
  "May kaiyaku present this capability to sever `:svc-id` at `:now-epoch`? Pure fn
  of the bundle metadata. Returns [ok? why]."
  [bundle {:keys [now-epoch svc-id]}]
  (cond
    (nil? bundle)
    [false "no capability (dry-run-only until a member issues one)"]

    (not (approved? bundle svc-id))
    [false (str "svc " (pr-str svc-id) " is not in the member-approved allowlist "
                "(G5: a tie the member did not approve is never severable)")]

    (>= now-epoch (->long (:exp bundle)))
    [false (str "capability expired (exp " (:exp bundle) " <= now " now-epoch ") — "
                "consent must be renewed; the driver falls back to dry-run")]

    :else
    [true (str "usable (svc " svc-id ", expires " (:exp bundle) ", aud " (:aud bundle) ")")]))

(defn validate-bundle
  "Validate a member-issued bundle's scope (raises cap-error on a bad one). The clj/
  lane receives the bundle in graph state rather than from a file."
  [bundle]
  (when bundle
    (when (not= (:capability bundle) capability)
      (throw (cap-error (str "capability " (pr-str (:capability bundle)) " != " (pr-str capability)))))
    (when (not= (:graph bundle) graph)
      (throw (cap-error (str "capability graph " (pr-str (:graph bundle)) " != " (pr-str graph)))))
    (when-not (str/starts-with? (str (:aud bundle "")) "did:")
      (throw (cap-error "capability audience must be a DID (the kotoba node)")))
    (when (contains? #{nil ""} (:nonce bundle))
      (throw (cap-error "capability must carry a nonce (replay protection)")))
    (when-not (sequential? (:approved bundle))
      (throw (cap-error "capability must carry an `approved` svc-id allowlist (G5)"))))
  bundle)

(defn audience [bundle] (:aud bundle))
