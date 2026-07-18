(ns kaiyaku.tests.test-issue-capability
  "kaiyaku 解約 — member-side issuance-tool tests (ADR-2606112201 R1).

  The tool is the MEMBER's signing runtime (not the actor); these prove the parts
  that must be correct for the leash to work end-to-end:
    - did:key from a raw Ed25519 pubkey is the canonical z6Mk… form + deterministic
    - the ISSUANCE↔VERIFICATION round-trip: a bundle the tool builds is ACCEPTED by
      methods/cap.cljc (load contract + usable?), and its `approved` allowlist binds
    - Ed25519 sign→verify roundtrips (and a tampered message fails) — JDK, clj-native
    - an unsigned build is honestly marked UNSIGNED; a signed build is member-signed
    - graph-cid is a deterministic CIDv1 (bafyrei… dag-cbor sha2-256)"
  (:require [clojure.test :refer [deftest is run-tests]]
            [kaiyaku.tools.issue-capability :as t]
            [kaiyaku.methods.cap :as cap]))

(def fixed-pub (vec (repeat 32 0)))   ; deterministic pubkey for the did:key vector

(defn- issue
  "Build a signed issuance for `approved`, returning [keypair result]."
  [approved & {:keys [exp-epoch] :or {exp-epoch 9999999999}}]
  (let [kp (t/gen-keypair)]
    [kp (t/build {:member-did (:did kp) :node-did "did:web:etzhayyim.com"
                  :graph-cid "graph:kaiyaku" :exp-iso "2026-07-21T00:00:00Z"
                  :exp-epoch exp-epoch :nonce "deadbeef" :approved approved
                  :private-key (:private kp)})]))

(deftest test-did-key-canonical-and-deterministic
  (let [d (t/did-key-from-pubkey fixed-pub)]
    (is (clojure.string/starts-with? d "did:key:z6Mk"))
    (is (= d (t/did-key-from-pubkey fixed-pub)))))   ; deterministic

(deftest test-b58-basic
  ;; base58btc: a single zero byte → "1"; deterministic.
  (is (= "1" (t/b58 [0])))
  (is (= (t/b58 [1 2 3]) (t/b58 [1 2 3]))))

(deftest test-b58-known-answers
  ;; KNOWN-ANSWER: encoder VALUE correctness (not just determinism) — a wrong-but-
  ;; consistent encoder would corrupt the member's did:key. Bitcoin base58 vectors.
  (is (= "11" (t/b58 [0 0])))                       ; leading zeros → leading '1's
  (is (= "2g" (t/b58 (mapv int (.getBytes "a" "UTF-8")))))   ; 0x61 = 97 = 1*58+39 → "2g"
  (is (= "1" (t/b58 [0]))))

(deftest test-base32-known-answers
  ;; RFC4648 base32 vectors (lowercase, no padding).
  (is (= "my" (t/base32-lower (mapv int (.getBytes "f" "UTF-8")))))
  (is (= "mzxw6ytboi" (t/base32-lower (mapv int (.getBytes "foobar" "UTF-8"))))))

(deftest test-issuance-verification-roundtrip
  ;; the bundle the tool builds must satisfy methods/cap.cljc's contract.
  (let [[_ r] (issue ["netflix" "spotify"])
        sc (:sidecar r)]
    (is (empty? (remove #(contains? sc %) cap/required-keys)))   ; full contract
    (is (= cap/capability (get sc "capability")))
    (is (= cap/graph (get sc "graph")))
    ;; G5-in-the-leash: usable for an approved svc, refused for an unapproved one
    (is (first (cap/usable? sc {:now-epoch 1000 :svc-id "netflix"})))
    (is (false? (first (cap/usable? sc {:now-epoch 1000 :svc-id "not-approved"}))))))

(deftest test-expiry-binds
  (let [[_ r] (issue ["netflix"] :exp-epoch 2000)
        sc (:sidecar r)]
    (is (first (cap/usable? sc {:now-epoch 1000 :svc-id "netflix"})))   ; before exp
    (is (false? (first (cap/usable? sc {:now-epoch 3000 :svc-id "netflix"}))))))  ; after exp

(deftest test-ed25519-sign-verify-roundtrip
  (let [kp (t/gen-keypair)
        msg "etzhayyim.com severance capability"
        sig (t/sign-b64 (:private kp) msg)]
    (is (true? (t/verify (:public kp) msg sig)))
    (is (false? (t/verify (:public kp) (str msg "-tampered") sig)))))

(deftest test-unsigned-vs-signed-status
  (let [unsigned (t/build {:member-did "did:key:zX" :node-did "did:web:etzhayyim.com"
                           :graph-cid "graph:kaiyaku" :exp-iso "2026-07-21T00:00:00Z"
                           :exp-epoch 9999999999 :nonce "n" :approved ["netflix"]})
        [_ signed] (issue ["netflix"])]
    (is (clojure.string/includes? (get-in unsigned [:sidecar "_status"]) "UNSIGNED"))
    (is (clojure.string/includes? (get-in signed [:sidecar "_status"]) "member-signed"))))

(deftest test-graph-cid-deterministic-cidv1
  (let [c (t/graph-cid "kaiyaku")]
    (is (clojure.string/starts-with? c "bafyrei"))   ; CIDv1 dag-cbor sha2-256 base32
    (is (= c (t/graph-cid "kaiyaku")))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-issue-capability)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
