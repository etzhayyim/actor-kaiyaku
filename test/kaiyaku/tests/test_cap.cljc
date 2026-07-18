(ns kaiyaku.tests.test-cap
  "kaiyaku 解約 — capability module unit tests (ADR-2606112201 R1).

  cap.cljc is the security-critical leash; it was only exercised INDIRECTLY (via
  issue_capability + driver) — this covers its own load/validation gate directly:
    - cap/load parses a member-issued JSON bundle and ACCEPTS a well-formed one
    - cap/load REJECTS every malformed bundle (missing key / wrong capability /
      wrong graph / non-DID aud / empty nonce / non-sequential approved) — the
      gate that stops a bad capability from ever reaching usable?
    - an absent bundle file → nil (fail-open to dry-run-only, never a crash)
    - approved? / usable? / issuance-template behave as the leash requires"
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [kaiyaku.methods.cap :as cap]))

(defn- valid-bundle []
  {"cacao_b64" "opaque-member-signed"
   "aud" "did:web:etzhayyim.com"
   "capability" cap/capability
   "graph" cap/graph
   "exp" 9999999999
   "nonce" "deadbeef"
   "approved" ["netflix" "spotify"]})

(defn- write-bundle! [m]
  (let [p (str (System/getProperty "java.io.tmpdir") "/kaiyaku-cap-" (gensym) ".json")]
    (spit p (json/generate-string m))
    p))

(deftest test-load-accepts-valid
  (let [p (write-bundle! (valid-bundle))]
    (try
      (let [b (cap/load p)]
        (is (= cap/capability (get b "capability")))
        (is (= ["netflix" "spotify"] (get b "approved"))))
      (finally (io/delete-file p true)))))

(deftest test-load-absent-file-is-nil
  ;; fail-open: no bundle → nil (dry-run-only), never a crash
  (is (nil? (cap/load (str (System/getProperty "java.io.tmpdir") "/kaiyaku-cap-absent-" (gensym) ".json")))))

(deftest test-load-rejects-malformed
  (doseq [[label mutate] {:missing-key   #(dissoc % "nonce")
                          :wrong-cap     #(assoc % "capability" "datom:transact")
                          :wrong-graph   #(assoc % "graph" "graph:other")
                          :non-did-aud   #(assoc % "aud" "not-a-did")
                          :empty-nonce   #(assoc % "nonce" "")
                          :approved-not-seq #(assoc % "approved" "netflix")}]
    (let [p (write-bundle! (mutate (valid-bundle)))]
      (try
        (is (thrown? clojure.lang.ExceptionInfo (cap/load p)) (str "must reject " label))
        (is (cap/cap-error?
             (try (cap/load p) (catch clojure.lang.ExceptionInfo e e)))
            (str label " must be a cap-error"))
        (finally (io/delete-file p true))))))

(deftest test-approved-and-usable
  (let [b (valid-bundle)]
    (is (true? (cap/approved? b "netflix")))
    (is (false? (cap/approved? b "unknown")))
    (is (first (cap/usable? b {:now-epoch 1000 :svc-id "netflix"})))
    (is (false? (first (cap/usable? b {:now-epoch 1000 :svc-id "unknown"}))))   ; off-allowlist
    (is (false? (first (cap/usable? (assoc b "exp" 500) {:now-epoch 1000 :svc-id "netflix"})))))) ; expired

(deftest test-issuance-template-shape
  (let [t (cap/issuance-template {:member-did "did:key:zABC" :node-did "did:web:etzhayyim.com"
                                  :graph-cid "bafyrei..." :exp-iso "2026-07-21T00:00:00Z"
                                  :nonce-hex "deadbeef" :approved ["netflix"]})]
    (is (= "did:key:zABC" (get t "iss")))                  ; the member signs (the on-record principal)
    (is (= "did:web:etzhayyim.com" (get t "aud")))         ; the node is the audience
    (is (= ["netflix"] (get t "approved")))
    (is (some #(clojure.string/includes? % cap/capability) (get t "resources")))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kaiyaku.tests.test-cap)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
