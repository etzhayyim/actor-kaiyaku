(ns kaiyaku.methods.plan
  "kaiyaku 解約 — severance-plan builder (dry-run only at R0).
  1:1 Clojure port of `methods/plan.py` (ADR-2606112201).

  Turns a :sever / :review-cascade decision on a tie into a concrete severance plan
  routed through the safest adapter tier (the karakuri ServiceOp tiering, ADR-2606039200):

    T1 official-API cancel      — service publishes a cancellation API
    T2 ToS-permitted browser    — browser-use headless plan over the MEMBER's OWN
                                  session; refused by construction when the service
                                  browser stance is :prohibited or :unknown (G3)
    T3 self-submit procedure    — generated checklist / 解約通知文 the member submits
                                  THEMSELVES (the toritsugi/kurashimori default-self-submit
                                  pattern); always available

  CONSTITUTIONAL (read before any change):
    G3 — ToS-honest, NO detection-evasion: evasion verbs (captcha-solve, proxy-rotate,
      stealth, rate-limit-bypass, fingerprint-spoof) are structurally unrepresentable —
      make-step raises on them. A :prohibited/:unknown browser stance falls to T3,
      never \"tries anyway\".
    G5/G6 — severance is DESTRUCTIVE: every plan requires member-sig + explicit dry-run
      confirm; execute raises at R0 (live execution = Council Lv6+ + operator gate).
    G8 — cost-of-severance honesty: notice period / 違約金 are carried into the plan and
      shown to the member; kaiyaku never plans around a contractual obligation.

  House style: Python ':…' keyword strings stay strings; pure fns; file I/O only at edges.
  Requires the existing kaiyaku analyze ns (analyze/load-file* + analyze/analyze)."
  (:require [clojure.string :as str]
            [kaiyaku.methods.analyze :as analyze]))

(def evasion-verbs
  #{"captcha-solve" "proxy-rotate" "stealth" "rate-limit-bypass"
    "fingerprint-spoof" "ip-rotate" "anti-bot-bypass"})

(def plannable #{":sever" ":review-cascade"})

(defn make-step
  "The only step constructor. Evasion verbs are unrepresentable (G3).
  Returns an ordered step map (key order: verb, detail, mode) for byte-parity JSON."
  [verb detail]
  (when (contains? evasion-verbs verb)
    (throw (ex-info (str "G3: detection-evasion verb '" verb "' is unrepresentable in kaiyaku")
                    {:verb verb :gate :G3})))
  (array-map "verb" verb "detail" detail "mode" "dry-run"))

(defn select-tier
  "Safest-first adapter routing (karakuri ADR-2606039200 pattern)."
  [svc]
  (let [cancel (or (get svc ":svc/cancel") {})]
    (cond
      (= (get cancel ":api") ":available") "T1"
      (= (get cancel ":browser") ":permitted") "T2"
      ;; :prohibited / :unknown browser stance refuses T2 by construction
      :else "T3")))

(defn build-plan
  "One severance plan for one tie. Dry-run only; never executes.
  Returns an ordered map mirroring the Python dict's key order exactly."
  [svc tie]
  (let [rec (get tie "recommendation")]
    (when-not (contains? plannable rec)
      (throw (ex-info (str "not plannable: recommendation " rec
                           " (only " (sort plannable) ")")
                      {:recommendation rec :gate :plannable})))
    (let [tier (select-tier svc)
          svc-id (get tie "svc")
          dependents (get tie "dependents")
          steps (cond-> []
                  (seq dependents)
                  (into (map (fn [d]
                               (make-step "rehome-dependency"
                                          (str "move " d " off " svc-id
                                               " (SSO/payment) BEFORE severing")))
                             dependents))

                  (= tier "T1")
                  (conj (make-step "api-cancel"
                                   (str "call the published cancellation API of " svc-id)))

                  (= tier "T2")
                  (conj (make-step "browser-cancel"
                                   (str "browser-use plan over the member's OWN session on "
                                        svc-id " (ToS-permitted surface only)")))

                  (= tier "T3")
                  (conj (make-step "self-submit"
                                   (str "generate 解約/退会 procedure + notice text for "
                                        svc-id "; the MEMBER submits it themselves"))))
          steps (-> steps
                    (conj (make-step "export-own-data"
                                     (str "T3 portability export of the member's own data from "
                                          svc-id " before closure")))
                    (conj (make-step "confirm-closure"
                                     "verify the service confirms 解約/退会 (email/record)")))]
      (array-map
       "svc" svc-id
       "svc_label" (get tie "svc_label")
       "tier" tier
       "recommendation" rec
       "steps" (vec steps)
       ;; G8 cost-of-severance honesty — carried, never planned around
       "notice_days" (get svc ":svc/notice-days" 0)
       "penalty_jpy" (get svc ":svc/penalty-jpy" 0)
       ;; G5 destructive gates — required before ANY live execution
       "requires" (array-map "member_sig" true
                             "dry_run_confirm" true
                             "council_lv6_operator_gate" true)
       "mode" "dry-run"))))

(defn plans
  "Build a severance plan for every plannable tie (:sever / :review-cascade)."
  [nodes edges]
  (let [res (analyze/analyze nodes edges)]
    (vec
     (for [tie (get res "ties")
           :when (contains? plannable (get tie "recommendation"))]
       (build-plan (get nodes (get tie "svc") {}) tie)))))

(defn execute
  "R0: live execution is Council Lv6+ + operator + member-sig gated (G5/G6)."
  [_plan]
  (throw (ex-info "kaiyaku R0: live severance execution is gated (G5/G6) — dry-run only"
                  {:gate :G5G6})))

;; ── report rendering (matches Python report's f-strings) ──────────────────────

(defn- comma-int
  "Python f'{n:,}' over an integer (group digits with commas)."
  [n]
  (let [s (str (long n))
        neg (str/starts-with? s "-")
        digits (if neg (subs s 1) s)
        grouped (->> (vec digits) reverse (partition-all 3)
                     (map #(apply str (reverse %))) reverse (str/join ","))]
    (str (when neg "-") grouped)))

(defn report
  "Render the severance-plans markdown (1:1 with Python report)."
  [ps]
  (let [L (transient ["# kaiyaku severance plans (dry-run — G5/G6 gated)" ""])]
    (doseq [p ps]
      (let [notice (get p "notice_days")
            penalty (get p "penalty_jpy")
            sev (if (or (and (number? notice) (not (zero? notice)))
                        (and (number? penalty) (not (zero? penalty))))
                  (str " · notice " notice "d · penalty ¥" (comma-int penalty))
                  "")]
        (conj! L (str "## " (get p "svc_label") " — " (get p "tier")
                      " (" (get p "recommendation") ")" sev))
        (doseq [[i s] (map-indexed vector (get p "steps"))]
          (conj! L (str (inc i) ". [" (get s "verb") "] " (get s "detail"))))
        (conj! L "")))
    (str (str/join "\n" (persistent! L)) "\n")))

;; ── minimal JSON encoder — matches Python json.dumps(ps, ensure_ascii=False, indent=1) ──

(defn- json-escape
  "Escape a string for JSON (ensure_ascii=False → non-ASCII passes through verbatim)."
  [s]
  (let [sb (StringBuilder.)]
    (doseq [c s]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        \formfeed (.append sb "\\f")
        \backspace (.append sb "\\b")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (str sb)))

(defn- json-scalar [v]
  (cond
    (string? v) (str "\"" (json-escape v) "\"")
    (true? v) "true"
    (false? v) "false"
    (nil? v) "null"
    (integer? v) (str (long v))
    :else (str v)))

(defn- json-encode
  "Encode v with Python json.dumps indent=1 semantics (newline + (level+1) spaces per item,
  ', ' → ',\\n' between items, ': ' after keys, closing bracket at current level's indent)."
  [v level]
  (let [ind (apply str (repeat (inc level) " "))
        cind (apply str (repeat level " "))]
    (cond
      (or (map? v) (instance? clojure.lang.IPersistentMap v))
      (if (empty? v)
        "{}"
        (str "{\n"
             (str/join ",\n"
                       (map (fn [[k val]]
                              (str ind "\"" (json-escape (str k)) "\": "
                                   (json-encode val (inc level))))
                            v))
             "\n" cind "}"))

      (sequential? v)
      (if (empty? v)
        "[]"
        (str "[\n"
             (str/join ",\n"
                       (map (fn [item] (str ind (json-encode item (inc level)))) v))
             "\n" cind "]"))

      :else (json-scalar v))))

(defn plans-json
  "Serialize the plans vector to JSON, byte-identical to Python
  json.dumps(ps, ensure_ascii=False, indent=1)."
  [ps]
  (json-encode ps 0))

#?(:clj
   (defn -main
     "CLI entry: build severance plans from a seed EDN ledger →
     out/severance-plans.md + out/severance-plans.json (file I/O at the edge)."
     [& argv]
     (let [argv (vec argv)
           here (delay (-> *file* clojure.java.io/file .getParentFile .getParentFile))
           seed (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                  (clojure.java.io/file (first argv))
                  (clojure.java.io/file @here "data" "seed-en-ledger.kotoba.edn"))
           outdir (if (some #{"--out"} argv)
                    (clojure.java.io/file (nth argv (inc (.indexOf argv "--out"))))
                    (clojure.java.io/file @here "out"))
           {:keys [nodes edges]} (analyze/load-file* seed)
           ps (plans nodes edges)]
       (.mkdirs outdir)
       (spit (clojure.java.io/file outdir "severance-plans.md") (report ps))
       (spit (clojure.java.io/file outdir "severance-plans.json") (plans-json ps))
       (println (str "kaiyaku: " (count ps) " severance plans (dry-run) → "
                     (clojure.java.io/file outdir "severance-plans.md")))
       0)))
