(ns kaiyaku.methods.handoff-ingest
  "kaiyaku 解約 — tate 盾 handoff ingest (wave 26, ADR-2606112201/2606112301).
  1:1 Clojure port of `methods/handoff_ingest.py`.

  tate の不利条項スキャンが :kaiyaku ルートで検出した自動更新条項・解約窓
  (out/kaiyaku-handoff.edn) を読み、縁-ledger 側の **notice-window ワークリスト**に
  変換する — tate detects → kaiyaku severs の配線が往復で閉じる。

  各候補は「この契約には自動更新/解約窓条項がある — 縁-ledger の該当 tie に
  :svc/notice-days をカレンダー化せよ」という ingest 指示で、severance の実行系
  (plan) には触れない (G5/G6 のゲートは不変)。

  Reuses kaiyaku.methods.analyze (read-edn — kaiyaku's own EDN reader) and, for the live
  dev e2e, tate.methods.terms-scan. House style: Python ':…' keyword strings stay strings;
  pure fns; file I/O behind #?(:clj …). Portable .cljc.

  NOTE: the Python __main__ CLI demo (main/argv handling) is ported as -main behind #?(:clj)."
  (:require [clojure.string :as str]
            [kaiyaku.methods.analyze :as analyze]
            [tate.methods.terms-scan :as terms-scan]
            #?(:clj [clojure.java.io :as io])))

(defn ingest
  "Parse tate's handoff EDN → notice-window candidates for the 縁-ledger."
  [handoff-text]
  (reduce
   (fn [out h]
     (if-not (and (map? h) (contains? h ":handoff/clause"))
       out
       (conj out
             {"doc" (get h ":handoff/doc")
              "jurisdiction" (get h ":handoff/jurisdiction" ":jp")
              "clause" (get h ":handoff/clause")
              "matched" (get h ":handoff/matched" "")
              "anchor" (get h ":handoff/anchor" "")
              "action" (get h ":handoff/action")})))
   []
   (analyze/read-edn handoff-text)))

(defn to-datoms
  "Emit the notice-window worklist as GROUND :add datoms (byte-identical to to_datoms)."
  ([cands] (to-datoms cands 1))
  ([cands tx]
   (let [L (transient
            [";; kaiyaku 解約 — tate handoff ingest datoms — GENERATED. DO NOT hand-edit."
             ";; GROUND :add — notice-window worklist (縁-ledger の :svc/notice-days 化候補)."
             ""])]
     (doseq [[i c] (map-indexed vector cands)]
       (let [eid (str "\"handoff:" (format "%03d" i) "\"")]
         (conj! L (str "[" eid " :kaiyaku.handoff/doc \"" (get c "doc") "\" " tx " :add]"))
         (conj! L (str "[" eid " :kaiyaku.handoff/jurisdiction " (get c "jurisdiction") " " tx " :add]"))
         (conj! L (str "[" eid " :kaiyaku.handoff/clause \"" (get c "clause") "\" " tx " :add]"))
         (conj! L (str "[" eid " :kaiyaku.handoff/action " (get c "action") " " tx " :add]"))))
     (conj! L "")
     (conj! L (str ";; candidates=" (count cands)))
     (str (str/join "\n" (persistent! L)) "\n"))))

(defn worklist-md
  "Render the ingest worklist markdown (1:1 with worklist_md)."
  [cands]
  (let [L (transient
           ["# kaiyaku — tate handoff 取込ワークリスト (notice-window カレンダー化候補)"
            ""
            "| doc | juris | clause | 開示アンカー |"
            "|---|---|---|---|"])]
    (doseq [c cands]
      (conj! L (str "| " (get c "doc") " | " (get c "jurisdiction") " | "
                    (get c "clause") " | " (get c "anchor") " |")))
    (conj! L "")
    (conj! L (str "各行は tate 盾 が member の契約に発見した自動更新/解約窓条項。縁-ledger の該当 tie に "
                  ":svc/notice-days を設定し、解約窓を逃さない (severance 実行は従来どおり G5/G6 ゲート)。"))
    (str (str/join "\n" (persistent! L)) "\n")))

#?(:clj
   (defn live-handoff-from-tate
     "開発時 e2e: tate からライブ生成して ingest。Port of _live_handoff_from_tate."
     []
     (let [[docs _] (terms-scan/load-docs)]
       (terms-scan/make-kaiyaku-handoff (terms-scan/scan docs (terms-scan/load-patterns))))))

#?(:clj
   (defn -main
     "CLI entry: tate handoff (file or live) → out/handoff-worklist.md + handoff-datoms.kotoba.edn."
     [& argv]
     (let [argv (vec argv)
           here (-> *file* io/file .getParentFile .getParentFile)
           outdir (if (some #{"--out"} argv)
                    (io/file (nth argv (inc (.indexOf argv "--out"))))
                    (io/file here "out"))
           text (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                  (slurp (str (first argv)))
                  (live-handoff-from-tate))
           cands (ingest text)]
       (.mkdirs outdir)
       (spit (io/file outdir "handoff-worklist.md") (worklist-md cands))
       (spit (io/file outdir "handoff-datoms.kotoba.edn") (to-datoms cands))
       (println (str "kaiyaku: " (count cands) " notice-window candidates ingested from tate "
                     "→ " (io/file outdir "handoff-worklist.md")))
       0)))
