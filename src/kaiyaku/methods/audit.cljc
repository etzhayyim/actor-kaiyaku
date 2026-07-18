#!/usr/bin/env bb
;; kaiyaku 解約 — G9 audit READ side: query the persisted authorization-receipt log.
(ns kaiyaku.methods.audit
  "audit.cljc — kaiyaku 解約 R1 audit query (ADR-2606112201 R1, G9).

  receipt.cljc WRITES authorization receipts to the kotoba commit-DAG; this is the
  READ side that closes the G9 loop — the member can ask 'what did kaiyaku
  authorize/refuse for service X, and was anything executed?'. It folds the
  append-only :db/add datoms back into per-receipt entity views (EAVT → entity)
  and answers queries over them.

  Pure fold over the log (file I/O only at the #?(:clj …) read edge). Because
  receipts carry executed=false ALWAYS (G6), the audit can independently VERIFY
  that no live cancellation was ever recorded — `executed-count` is a standing 0
  check, not a claim."
  (:require [clojure.string :as str]
            [kaiyaku.methods.kotoba :as k]))

(defn entities-from-datoms
  "Fold a flat seq of [op e a v] datoms into {entity {attr value}} (last-write-wins
  per attr; receipt entity ids are unique per (svc, as-of) so no cross-clobber)."
  [datoms]
  (reduce (fn [m [op e a v]]
            (if (= op ":db/add") (assoc-in m [e a] v) m))
          {} datoms))

#?(:clj
   (defn entities-from-log [log-path]
     (entities-from-datoms (mapcat #(get % ":tx/datoms") (k/read-log log-path)))))

(defn- receipt-entities
  "From an {entity attrs} map → the receipt entities (id starts 'receipt:'), each
  as its attrs map plus :id."
  [entities]
  (->> entities
       (filter (fn [[e _]] (str/starts-with? (str e) "receipt:")))
       (map (fn [[e attrs]] (assoc attrs ":id" e)))
       vec))

#?(:clj
   (defn receipts
     "All authorization receipts recorded in the log (entity views)."
     [log-path]
     (receipt-entities (entities-from-log log-path))))

(defn receipts-for-svc
  "Receipts about a specific service (over an already-loaded receipt seq)."
  [rs svc]
  (filterv #(= svc (get % ":kaiyaku.receipt/svc")) rs))

(defn audit-summary
  "Fold a receipt seq into a standing audit answer. `:executed` is a VERIFICATION
  (G6: it must be 0 — kaiyaku never records a live cancellation), not a claim."
  [rs]
  {:total (count rs)
   :authorized (count (filter #(true? (get % ":kaiyaku.receipt/authorized")) rs))
   :refused (count (filter #(= ":refused" (get % ":kaiyaku.receipt/status")) rs))
   :executed (count (filter #(true? (get % ":kaiyaku.receipt/executed")) rs))
   :by-status (frequencies (map #(get % ":kaiyaku.receipt/status") rs))
   :server-signed (count (filter #(true? (get % ":kaiyaku.receipt/server-signed")) rs))})

(defn no-live-execution?
  "The standing G6 verification over the audit log: no receipt ever recorded a
  live cancellation, and none was server-signed. True iff the log is clean."
  [rs]
  (let [s (audit-summary rs)]
    (and (zero? (:executed s)) (zero? (:server-signed s)))))

#?(:clj
   (defn -main
     "CLI: print an audit summary over a receipt log (arg: log path)."
     [& argv]
     (let [path (first argv)]
       (if-not path
         (do (println "usage: audit.cljc <receipt-log.edn>") 1)
         (let [rs (receipts path)
               s (audit-summary rs)]
           (println (str "kaiyaku audit (" path "): " (:total s) " receipts · authorized "
                         (:authorized s) " · refused " (:refused s)
                         " · executed " (:executed s) " (G6: must be 0) · clean="
                         (no-live-execution? rs)))
           0)))))
