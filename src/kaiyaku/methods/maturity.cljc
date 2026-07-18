#!/usr/bin/env bb
;; kaiyaku 解約 — generated R1 MATURITY scorecard (do not hand-edit MATURITY.md).
(ns kaiyaku.methods.maturity
  "maturity.cljc — kaiyaku 解約 R1 MATURITY scorecard generator (ADR-2606112201 R1).

  Consolidates the R1 state into one honest, GENERATED scorecard (the repo pattern:
  rasen/tate/hinagata MATURITY.md) from two SoTs — the manifest (:actor/r1 + gates)
  and the cancellation-procedure catalog — plus the honest, static R1 gaps. A
  freshness test pins MATURITY.md to this generator's output, so the scorecard can
  never silently drift from the manifest/catalog it summarizes.

  Deterministic: every count map is rendered in sorted-key order (frequencies'
  map order is otherwise unstable), so the output is byte-stable for the freshness
  comparison. File I/O only at the #?(:clj …) edge."
  (:require [clojure.string :as str]
            [kaiyaku.methods.catalog :as catalog]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io])))

(defn- sorted-counts
  "Render a {k n} map as 'k1 n1, k2 n2' in sorted-key order (byte-stable)."
  [m]
  (str/join ", " (map (fn [k] (str (name k) " " (get m k))) (sort (keys m)))))

(defn scorecard
  "Build the MATURITY markdown from a parsed manifest + catalog entries."
  [manifest entries]
  (let [cov (catalog/coverage entries)
        gaps (catalog/category-gaps entries)
        r1 (:actor/r1 manifest)]
    (str
     "# kaiyaku 解約 — R1 MATURITY (GENERATED — do not hand-edit; see methods/maturity.cljc)\n\n"
     "ADR-2606112201 R1. The driver AUTHORIZES; a post-R1 component executes (G6) — there is\n"
     "NO live cancellation I/O in this codebase (`plan/execute` raises). Dry-run throughout.\n\n"

     "## R1 execution-leg components\n\n"
     (str/join "\n"
               (map (fn [k]
                      (str "- [x] **" (name k) "** — " (get-in r1 [k :note])))
                    (sort (keys r1))))
     "\n\n"

     "## Cancellation-procedure catalog (:representative)\n\n"
     "- services: " (:total cov) "\n"
     "- tier mix: " (sorted-counts (:by-tier cov)) "\n"
     "- region: " (sorted-counts (:by-region cov)) "\n"
     "- category coverage: " (count (:covered gaps)) "/"
     (+ (count (:covered gaps)) (count (:missing gaps)))
     " (" (:pct gaps) "%)"
     (when (seq (:missing gaps)) (str " — missing: " (sorted-counts (frequencies (sort (:missing gaps)))))) "\n"
     "- **operator-verified: " (:operator-verified cov) " / " (:total cov)
     "** (G6: every entry must be operator-verified before live use)\n\n"

     "## Gates\n\n"
     (str/join "\n"
               (map (fn [g] (str "- **" (:gate/id g) "** " (:gate/name g)))
                    (:actor/gates manifest)))
     "\n\n"

     "## Honest gaps (R1)\n\n"
     "- live execution is NOT wired — the driver returns authorization descriptors with\n"
     "  `executed=false`; `plan/execute` raises (G6: Council Lv6+ + operator + member capability).\n"
     "- catalog is `:representative` — operator-verified = 0; an operator must verify each\n"
     "  procedure (and ToS stance) against its disclosed source before any live use.\n"
     "- CBOR-CACAO byte-parity vs the live kotoba node is the operator step (cap bundle uses a\n"
     "  canonical-JSON envelope in `tools/issue_capability.cljc`).\n"
     "- clj/ langgraph lane: R1 functionally equivalent — `kaiyaku.cap` + `kaiyaku.driver` +\n"
     "  `kaiyaku.catalog` clj-native, wired into agent.cljc as the :dispatch node (:approve →\n"
     "  :dispatch → :rehearse, capability-gated authorization + catalog-enriched descriptor,\n"
     "  executed=false; `clojure -X:test`). Both lanes carry R1; catalog EDN is shared.\n")))

#?(:clj
   (defn generate
     "Read the actor's manifest + catalog → the MATURITY scorecard markdown."
     [actor-dir]
     (let [manifest (edn/read-string (slurp (io/file actor-dir "manifest.edn")))
           entries (catalog/load-file* (io/file actor-dir "data" "cancel-procedures.kotoba.edn"))]
       (scorecard manifest entries))))

#?(:clj
   (defn -main
     "CLI: (re)generate MATURITY.md from the manifest + catalog."
     [& _]
     (let [actor-dir (-> *file* io/file .getParentFile .getParentFile)
           md (generate actor-dir)]
       (spit (io/file actor-dir "MATURITY.md") md)
       (println (str "kaiyaku: MATURITY.md regenerated (" (count md) " bytes)"))
       0)))
