#!/usr/bin/env bb
;; kaiyaku 解約 — autonomous heartbeat: load the 縁-ledger → append GROUND datoms.
(ns kaiyaku.methods.autorun
  "autorun.cljc — kaiyaku 解約 deterministic heartbeat (ADR-2606112201, tate/hinagata pattern).

  One beat: load the member's 縁-ledger seed graph, build the GROUND EAVT datoms
  (:svc/* + :member/* NODES — including the nested :svc/cancel sub-keys — and the :en/*
  ties), and APPEND them as ONE content-addressed transaction to the append-only ENKIRI
  LEDGER (kotoba.cljc). prev-cid chaining keeps the ledger tamper-evident + resume-safe.

  GROUND ONLY: per-tie burden / recommendation / plan-tier are DERIVED (computed on read,
  G2) and are NEVER persisted — so a stale 'severable' verdict can never outlive the facts.
  Deterministic by construction: caller supplies tx-id + as-of (no wall clock,
  no Math/random); nodes are emitted in sorted-key order + edges in index order (mirrors
  datom_emit) → resume-safe. IDEMPOTENT-BY-CONTENT: a beat whose ground datoms equal the
  previous beat's is a NO-OP. No-server-key: appends to a local file only, no network I/O.
  G1/N1: the ledger records SERVICE ties only, never a person/relationship."
  (:require [kaiyaku.methods.analyze :as analyze]
            [kaiyaku.methods.datom-emit :as de]
            [kaiyaku.methods.kotoba :as k]
            #?(:clj [clojure.java.io :as io])))

(defn ground-datoms-from
  "Build the durable GROUND EAVT datoms from a loaded {:nodes :edges} graph, in the
  canonical [\":db/add\" e a v] form — mirroring datom_emit's GROUND stratum exactly
  (sorted node keys + nested :svc/cancel sub-keys + index-keyed :en/NNN ties). Derived
  burden/recommendation readouts are excluded by design (G2)."
  [{:keys [nodes edges]}]
  (let [out (transient [])]
    ;; ── nodes (sorted-key order → deterministic)
    (doseq [nid (sort (keys nodes))]
      (let [n (get nodes nid)]
        (doseq [a de/node-attrs :when (and (contains? n a) (not (map? (get n a))))]
          (conj! out (k/add nid a (get n a))))
        (let [cancel (get n ":svc/cancel")]
          (when (map? cancel)
            (doseq [ck (sort (keys cancel))]
              (conj! out (k/add nid (str ":svc/cancel" ck) (get cancel ck))))))))
    ;; ── 縁 (ties; entity id content-stable: en:NNN by index, mirrors datom_emit)
    (doseq [[i e] (map-indexed vector edges)]
      (let [eid (str "en:" (format "%03d" i))]
        (doseq [a de/edge-attrs :when (contains? e a)]
          (conj! out (k/add eid a (get e a))))))
    (persistent! out)))

#?(:clj
   (def ^:private here-dir
     (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile)))

#?(:clj
   (defn default-seed-path []
     (str (io/file here-dir "data" "seed-en-ledger.kotoba.edn"))))

#?(:clj
   (defn ground-datoms
     ([] (ground-datoms (default-seed-path)))
     ([seed-path] (ground-datoms-from (analyze/load-file* seed-path)))))

(defn beat
  "Run one heartbeat. opts:
     :datoms   ground datoms (optional; defaults to (ground-datoms))
     :tx-id    deterministic tx id (required)
     :as-of    deterministic as-of stamp (required)
     :log-path enkiri-ledger path (required)
   IDEMPOTENT-BY-CONTENT: if the new ground datoms equal the last beat's datoms,
   the beat is a NO-OP — nothing is appended.
   Returns {:head <cid> :count <n> :appended <bool> :reason <kw|nil>}."
  [{:keys [datoms tx-id as-of log-path]}]
  (let [ds (or datoms (ground-datoms))
        prev (k/head-cid log-path)
        last-ds (let [txs (k/read-log log-path)]
                  (when (seq txs) (get (last txs) ":tx/datoms")))
        unchanged? (= ds last-ds)
        base {:count (count ds)}]
    (if unchanged?
      (assoc base :head prev :appended false :reason :no-change)
      (let [tx (k/make-tx ds tx-id as-of prev)
            head (k/append-tx tx log-path)]
        (assoc base :head head :appended true :reason nil)))))

#?(:clj
   (defn -main [& args]
     (let [log-path (or (first args)
                        (str (io/file here-dir "data" "persisted" "kaiyaku.enkiri-ledger.kotoba.edn")))
           r (beat {:tx-id "kaiyaku-beat-manual" :as-of "manual" :log-path log-path})]
       (println (str "enkiri ledger head=" (:head r)
                     " datoms=" (:count r)
                     " appended=" (:appended r)
                     (when (:reason r) (str " (" (name (:reason r)) ")"))))
       (println (str "chain=" (k/verify-chain log-path))))))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (apply -main *command-line-args*)))
