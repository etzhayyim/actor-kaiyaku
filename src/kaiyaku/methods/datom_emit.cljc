(ns kaiyaku.methods.datom-emit
  "kaiyaku 解約 — kotoba Datom-log emitter (canonical EAVT state, ADR-2605312345).
  1:1 Clojure port of `methods/datom_emit.py` (ADR-2606112201).

  Projects the 縁-ledger into append-only kotoba Datoms [e a v tx op]. Two strata:

    GROUND (durable, op :add) — the :svc/* / :member/* nodes and :en/* ties. This IS
      the Datom log (synthetic demo seed at R0; live per-member facts are consent- +
      G7-gated).

    DERIVED (transient, :bond/is-transient true) — burden / recommendation / plan-tier
      readouts. Per G2 these are computed on READ and never stored as ground state.

  Reuses kaiyaku.methods.analyze (read-edn / load-graph / load-file* / analyze). House
  style: Python ':…' keyword strings stay strings; the emitted Datom text is byte-identical
  to the Python emit. Float formatting mirrors Python's `{v:g}`.

  NODE ORDERING: the Python emitter walks nodes via `for nid in sorted(nodes)` (lexical
  sort), so insertion order is irrelevant — emit sorts node ids itself."
  (:require [clojure.string :as str]
            [kaiyaku.methods.analyze :as analyze]
            #?(:clj [clojure.java.io :as io])))

;; attributes promoted from each node map into ground datoms (stable order = determinism)
(def node-attrs
  [":svc/label" ":svc/kind" ":svc/category" ":svc/sourcing"
   ":svc/notice-days" ":svc/penalty-jpy"
   ":member/label" ":member/sourcing"])

(def edge-attrs
  [":en/from" ":en/to" ":en/kind" ":en/monthly-cost-jpy"
   ":en/usage-score" ":en/last-used-days" ":en/first-seen"
   ":en/dep" ":en/sourcing"])

(defn- fmt-g
  "Mirror Python's f-string `{v:g}` for our (moderate-magnitude) doubles: 6 significant
  digits, trailing zeros stripped, an integral value renders with no decimal point."
  [v]
  (let [d (double v)]
    (if (and (not (Double/isInfinite d)) (not (Double/isNaN d))
             (== d (Math/rint d)) (< (Math/abs d) 1e15))
      (str (long d))
      (let [s (format "%.6g" d)]
        (if (str/includes? s ".")
          (-> s (str/replace #"0+$" "") (str/replace #"\.$" ""))
          s)))))

(defn fmt
  "Port of _fmt: bool → true/false; nil → nil; \":…\" kept literal; other string → quoted
  with \\ and \" escaped; float (double) → {v:g}; else str()."
  [v]
  (cond
    (true? v) "true"
    (false? v) "false"
    (nil? v) "nil"
    (string? v) (if (str/starts-with? v ":")
                  v
                  (str "\"" (-> v (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))
    (double? v) (fmt-g v)
    :else (str v)))

;; ── select_tier (plan.py select_tier — inlined; only the cancel-stance read is needed here)
(defn select-tier
  "Safest-first adapter routing (karakuri ADR-2606039200 pattern), 1:1 with plan.select_tier."
  [svc]
  (let [cancel (or (get svc ":svc/cancel") {})]
    (cond
      (= (get cancel ":api") ":available") "T1"
      (= (get cancel ":browser") ":permitted") "T2"
      :else "T3")))

(defn emit
  "Faithful 1:1 of datom_emit.emit. Returns the kotoba Datom-log EDN text (trailing newline)."
  ([nodes edges res] (emit nodes edges res 1))
  ([nodes edges res tx]
   (let [L (transient [])]
     (conj! L ";; kaiyaku 解約 — GENERATED kotoba Datom log (ADR-2606112201). DO NOT hand-edit.")
     (conj! L ";; Canonical EAVT state (ADR-2605312345). [e a v tx op].")
     (conj! L ";; GROUND op :add = durable. DERIVED :bond/is-transient = computed on read (G2).")
     (conj! L "")
     (conj! L ";; ── GROUND: nodes")
     (doseq [nid (sort (keys nodes))]
       (let [n (get nodes nid)]
         (doseq [a node-attrs]
           (when (and (contains? n a) (not (map? (get n a))))
             (conj! L (str "[" (fmt nid) " " a " " (fmt (get n a)) " " tx " :add]"))))
         (let [cancel (get n ":svc/cancel")]
           (when (map? cancel)
             (doseq [k (sort (keys cancel))]
               (conj! L (str "[" (fmt nid) " :svc/cancel" k " " (fmt (get cancel k)) " " tx " :add]")))))))
     (conj! L "")
     (conj! L ";; ── GROUND: 縁 (ties)")
     (doseq [[i e] (map-indexed vector edges)]
       (let [eid (str "\"en:" (format "%03d" i) "\"")]
         (doseq [a edge-attrs]
           (when (contains? e a)
             (conj! L (str "[" eid " " a " " (fmt (get e a)) " " tx " :add]"))))))
     (conj! L "")
     (conj! L ";; ── DERIVED (transient — burden/recommendation computed on read, G2)")
     (doseq [t (get res "ties")]
       (let [eid (fmt (str "readout:" (get t "svc")))]
         (conj! L (str "[" eid " :bond/is-transient true " tx " :add]"))
         (conj! L (str "[" eid " :enkiri/burden " (fmt-g (get t "burden")) " " tx " :add]"))
         (conj! L (str "[" eid " :enkiri/recommendation " (get t "recommendation") " " tx " :add]"))
         (conj! L (str "[" eid " :enkiri/plan-tier \"" (select-tier (get nodes (get t "svc"))) "\" " tx " :add]"))))
     (conj! L "")
     (conj! L (str ";; ties=" (count (get res "ties")) " recoverable-jpy-mo="
                   (fmt-g (get res "recoverable_monthly_jpy"))))
     (str (str/join "\n" (persistent! L)) "\n"))))

#?(:clj
   (defn -main
     "CLI entry: analyze a seed EDN ledger → out/enkiri-datoms.kotoba.edn (file I/O at the edge)."
     [& argv]
     (let [argv (vec argv)
           here (-> *file* io/file .getParentFile .getParentFile)
           seed (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                  (io/file (first argv))
                  (io/file here "data" "seed-en-ledger.kotoba.edn"))
           outdir (if (some #{"--out"} argv)
                    (io/file (nth argv (inc (.indexOf argv "--out"))))
                    (io/file here "out"))
           tx (if (some #{"--tx"} argv)
                (Long/parseLong (nth argv (inc (.indexOf argv "--tx"))))
                1)
           {:keys [nodes edges]} (analyze/load-file* seed)
           res (analyze/analyze nodes edges)
           out (io/file outdir "enkiri-datoms.kotoba.edn")
           text (emit nodes edges res tx)]
       (.mkdirs outdir)
       (spit out text)
       (println (str "kaiyaku: " (count (re-seq #":add" text)) " datoms → " out))
       0)))
