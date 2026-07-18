#!/usr/bin/env bb
;; kaiyaku 解約 — 縁-ledger DATOM-LOG writer (content-addressed commit-DAG).
(ns kaiyaku.methods.kotoba
  "kotoba.cljc — kaiyaku 解約 append-only ENKIRI (縁-ledger) LEDGER writer
  (ADR-2606112201, on ADR-2605262130 + ADR-2605312345; same content-addressed
  commit-DAG machinery as the tate/hinagata/chigiri/kadode/ugachi/busshi family —
  the datomic-isomorphic canonical-state layer kaiyaku previously only EMITTED
  (datom_emit) but never PERSISTED).

  Persists kaiyaku's GROUND datoms — the member's service (:svc/*) + member
  (:member/*) NODES and their :en/* ties (the 縁) — to a local append-only EDN log
  of content-addressed transactions. DERIVED state (per-tie burden / recommendation
  / plan-tier, computed on READ, G2) is NEVER stored: so a stale 'this tie is
  severable' verdict can never outlive the facts beneath it.

  Each tx CID = 'b' + sha256-hex over the canonical JSON
  {\"datoms\":[…],\"prev\":…} (keys stay ':…' STRINGS; EAVT = [op entity attr
  value], op :db/add only). prev-cid chaining makes the log tamper-evident
  (verify-chain). Deterministic: caller supplies tx-id + as-of (no wall clock)
  → resume-safe.

  No-server-key: this writer holds no key and performs NO network I/O — it only
  appends to a local file. G1: the ledger records the member's OWN service ties
  (synthetic at R0), never a person/relationship (N1 — a tie target is always a
  SERVICE); severance is dry-run + member-signed (execute() is Council-gated)."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

(defn add [entity attr value] [":db/add" entity attr value])

;; ── canonical JSON for the content-address (family-consistent) ───────────────
(defn- py-float-str [x] (str (double x)))

(defn- json-escape [s]
  (let [sb (StringBuilder.)]
    (doseq [c (str s)]
      (let [code (int c)]
        (cond
          (= c \") (.append sb "\\\"")
          (= c \\) (.append sb "\\\\")
          (= c \newline) (.append sb "\\n")
          (= c \return) (.append sb "\\r")
          (= c \tab) (.append sb "\\t")
          (< code 0x20) (.append sb (format "\\u%04x" code))
          :else (.append sb c))))
    (str sb)))

(defn- json-val [v]
  (cond
    (boolean? v) (if v "true" "false")
    (nil? v) "null"
    (integer? v) (str v)
    (float? v) (py-float-str v)
    (string? v) (str \" (json-escape v) \")
    (sequential? v) (str "[" (str/join "," (map json-val v)) "]")
    :else (str v)))

(defn- canonical [datoms prev-cid]
  (str "{\"datoms\":[" (str/join "," (map json-val datoms)) "],\"prev\":" (json-val prev-cid) "}"))

(defn- sha256-hex [^String s]
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) b))))

(defn tx-cid
  ([datoms] (tx-cid datoms ""))
  ([datoms prev-cid] (str "b" (sha256-hex (canonical datoms prev-cid)))))

(defn make-tx [datoms tx-id as-of prev-cid]
  {":tx/id" tx-id ":tx/as-of" as-of ":tx/prev" prev-cid
   ":tx/cid" (tx-cid datoms prev-cid) ":tx/count" (count datoms) ":tx/datoms" datoms})

;; ── EDN log serialization ────────────────────────────────────────────────────
(defn- edn-val [v]
  (cond
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)
    (float? v) (py-float-str v)
    (string? v) (if (str/starts-with? v ":") v (str \" (json-escape v) \"))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (str v)))

(defn tx->edn [tx]
  (let [datoms (str/join " " (map (fn [d] (str "[" (str/join " " (map edn-val d)) "]"))
                                  (get tx ":tx/datoms")))]
    (str "{:tx/id " (get tx ":tx/id") " :tx/as-of " (get tx ":tx/as-of")
         " :tx/prev " (str \" (json-escape (get tx ":tx/prev")) \")
         " :tx/cid " (str \" (json-escape (get tx ":tx/cid")) \")
         " :tx/count " (get tx ":tx/count") " :tx/datoms [" datoms "]}")))

;; ── minimal EDN reader (subset), self-contained ──────────────────────────────
(def ^:private tok-re #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn- tokens [s]
  (let [m (re-matcher tok-re s)]
    ((fn step []
       (lazy-seq
        (when (.find m)
          (let [t (.group m 1)] (if (nil? t) (step) (cons t (step))))))))))

(defn- atom-of [t]
  (cond
    (str/starts-with? t "\"") (-> (subs t 1 (dec (count t)))
                                  (str/replace "\\\"" "\"") (str/replace "\\\\" "\\"))
    (= t "true") true
    (= t "false") false
    (= t "nil") nil
    (str/starts-with? t ":") t
    :else (let [l (try (Long/parseLong t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
            (if (not= l ::nan) l
                (let [d (try (Double/parseDouble t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
                  (if (not= d ::nan) d t))))))

(def ^:private end-marker ::end)

(defn- parse-step [toks i]
  (let [t (nth toks i) i (inc i)]
    (cond
      (= t "[") (loop [i i out []] (let [[x i] (parse-step toks i)]
                                     (if (= x end-marker) [out i] (recur i (conj out x)))))
      (= t "{") (loop [i i out {}]
                  (let [[k i] (parse-step toks i)]
                    (if (= k end-marker) [out i]
                        (let [[v i] (parse-step toks i)] (recur i (assoc out k v))))))
      (or (= t "]") (= t "}")) [end-marker i]
      :else [(atom-of t) i])))

(defn parse-edn [s] (first (parse-step (vec (tokens s)) 0)))

#?(:clj
   (do
     (defn append-tx [tx log-path]
       (let [f (io/file log-path)]
         (when-let [p (.getParentFile f)] (.mkdirs p))
         (when-not (.exists f)
           (spit f (str ";; kaiyaku 解約 — ENKIRI (縁-ledger) LEDGER (append-only, content-addressed "
                        "EAVT commit-DAG of the member's OWN service ties). Generated; DO NOT hand-edit. "
                        "A record of SERVICE ties (never a person — N1); severance is dry-run + member-signed. "
                        "ADR-2606112201 / ADR-2605312345.\n")))
         (spit f (str (tx->edn tx) "\n") :append true)
         (get tx ":tx/cid")))

     (defn read-log [log-path]
       (let [f (io/file log-path)]
         (if-not (.exists f) []
                 (->> (str/split-lines (slurp f))
                      (map str/trim)
                      (remove #(or (empty? %) (str/starts-with? % ";")))
                      (mapv parse-edn)))))

     (defn head-cid [log-path]
       (let [txs (read-log log-path)] (if (seq txs) (get (last txs) ":tx/cid") "")))

     (defn verify-chain [log-path]
       (let [txs (read-log log-path) n (count txs)]
         (loop [i 0 prev "" ts txs]
           (if (empty? ts) {:ok true :length n :broken-at -1}
               (let [tx (first ts) expect (tx-cid (get tx ":tx/datoms") prev)]
                 (if (or (not= (get tx ":tx/cid") expect) (not= (get tx ":tx/prev") prev))
                   {:ok false :length n :broken-at i}
                   (recur (inc i) (get tx ":tx/cid") (rest ts))))))))))
