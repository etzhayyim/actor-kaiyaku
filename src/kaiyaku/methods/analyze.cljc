(ns kaiyaku.methods.analyze
  "kaiyaku 解約 — edge-primary tie-burden analyzer over the member's 縁-ledger.
  1:1 Clojure port of `methods/analyze.py` (ADR-2606112201).

  Reads a kotoba-EDN 縁-ledger (:svc/* + :member/* nodes, :en/* 縁) and surfaces —
  per TIE, never per member — where unused paid ties (sub-scriptions, dormant accounts,
  recurring card charges) accumulate burden, routed to RELEASE (縁切り = the member
  severing their OWN unused service ties), with a dependency cascade-guard.

  CONSTITUTIONAL (read before any change):
    G2 — edge-primary. The severance decision lives ONLY on the :en/* tie (burden =
      monthly cost × unused fraction + dormancy, computed on READ). There is no
      per-member score, no score-of-soul, no \"toxic person\" rating (反個人主義).
    G1 — member-principal, own ties only. The ledger is the MEMBER's own service ties
      (synthetic demo seed at R0); never a third party's, never another person.
    N1 — human relationships are NOT in this ledger. :en/to is always a SERVICE.
    G8 — honesty: recommendations mirror the disclosed organizer thresholds
      (ADR usageScore<20 ∧ cost>500 → :sever; <50 → :review); notice/penalty are
      surfaced as cost-of-severance, never advised around.

  House style: Python ':…' keyword strings stay strings (incl. all :svc/* / :en/* attrs);
  pure fns; file I/O only at edges via clojure.java.io. Portable .cljc."
  (:require [clojure.string :as str]))

;; ── minimal EDN reader (subset: vectors [], maps {}, :keyword, \"string\", num, bool, nil)
;; Mirrors analyze.py's _TOK / _tokens / _atom / _parse faithfully. Keywords are kept as
;; \":ns/name\" strings (NOT clojure keywords) so the whole pipeline stays string-keyed,
;; byte-for-byte the same as the Python port.

(def ^:private tok-re
  ;; _TOK = re.compile(r'[\s,]+|;[^\n]*|(\[|\]|\{|\}|"(?:\\.|[^"\\])*"|[^\s,\[\]{}]+)')
  #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn tokens
  "Lazy seq of significant tokens (group 1 of each tok-re match that captured)."
  [s]
  (let [m (re-matcher tok-re s)]
    ((fn step []
       (lazy-seq
        (when (.find m)
          (let [t (.group m 1)]
            (if (nil? t)
              (step)
              (cons t (step))))))))))

(defn atom-of
  "Port of _atom: \"…\" → unescaped string; true/false/nil → bool/nil; \":…\" kept as string;
  int → long; else float; else raw string."
  [t]
  (cond
    (str/starts-with? t "\"")
    (-> (subs t 1 (dec (count t)))
        (str/replace "\\\"" "\"")
        (str/replace "\\\\" "\\"))
    (= t "true") true
    (= t "false") false
    (= t "nil") nil
    (str/starts-with? t ":") t
    :else
    (let [as-long (try (Long/parseLong t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
      (if (not= as-long ::nan)
        as-long
        (let [as-dbl (try (Double/parseDouble t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
          (if (not= as-dbl ::nan) as-dbl t))))))

(def ^:private end-marker ::end)

(defn- parse-step
  "Consume one form from the token vector at index i. Returns [value next-i] or
  [end-marker next-i] when a closing ] or } is hit (matching _parse's _END sentinel)."
  [toks i]
  (let [t (nth toks i)
        i (inc i)]
    (cond
      (= t "[")
      (loop [i i, out []]
        (let [[x i] (parse-step toks i)]
          (if (= x end-marker)
            [out i]
            (recur i (conj out x)))))

      (= t "{")
      (loop [i i, out {}]
        (let [[k i] (parse-step toks i)]
          (if (= k end-marker)
            [out i]
            (let [[v i] (parse-step toks i)]
              (recur i (assoc out k v))))))

      (or (= t "]") (= t "}"))
      [end-marker i]

      :else
      [(atom-of t) i])))

(defn read-edn
  "Parse the first top-level form from EDN text (matches read_edn → _parse(_tokens(text)))."
  [text]
  (let [toks (vec (tokens text))]
    (first (parse-step toks 0))))

(def member-tie-kinds #{":subscribes" ":holds-account" ":recurring-charge"})
(def dependency-kinds #{":depends-on"})

;; disclosed organizer thresholds (organizer CLAUDE.md monthly analysis — mirrored, not invented)
(def SEVER-USAGE 20)
(def SEVER-COST-JPY 500)
(def REVIEW-USAGE 50)
;; dormant-account thresholds (cost-free :holds-account ties)
(def DORMANT-SEVER-DAYS 365)
(def DORMANT-REVIEW-DAYS 180)

(defn load-graph
  "Return {:nodes nodes-by-id :edges edges} from a parsed list of EDN forms.
  (`load` is a clojure.core fn — named load-graph; the host edge reads the file.)
  Insertion order of nodes is preserved (ordered map) to match Python dict order."
  [forms]
  (reduce
   (fn [{:keys [nodes edges] :as acc} f]
     (cond
       (not (map? f)) acc
       (contains? f ":svc/id") (assoc-in acc [:nodes (get f ":svc/id")] f)
       (contains? f ":member/id") (assoc-in acc [:nodes (get f ":member/id")] f)
       (and (contains? f ":en/from") (contains? f ":en/to"))
       (update acc :edges conj f)
       :else acc))
   {:nodes (array-map) :edges []}
   forms))

#?(:clj
   (defn load-file*
     "Read + parse a 縁-ledger EDN graph file → {:nodes :edges}. File I/O only at this edge."
     [path]
     (load-graph (read-edn (slurp (str path))))))

(defn- num-or
  "float(x or 0) — coerce to double; nil/false/0/missing → 0.0. Mirrors
  `float(tie.get(k, 0) or 0)` (Python: a falsy value, incl. 0, becomes the `or 0` 0)."
  [m k]
  (let [v (get m k)]
    (if (or (nil? v) (false? v) (and (number? v) (zero? v))) 0.0 (double v))))

(defn dependents
  "svc-id → [svc-ids that depend on it] (SSO / payment-method cascade inputs).
  Mirrors _dependents: a defaultdict(list); ::order metadata records first-touch key order."
  [edges]
  (reduce
   (fn [deps e]
     (if (contains? dependency-kinds (get e ":en/kind"))
       (let [to (get e ":en/to")
             from (get e ":en/from")
             had? (contains? deps to)
             deps' (update deps to (fnil conj []) from)]
         (if had?
           (with-meta deps' (meta deps))
           (with-meta deps' (update (meta deps) ::order (fnil conj []) to))))
       deps))
   ^{::order []} {}
   edges))

(defn burden
  "Tie burden, computed on read (G2): paid waste + dormancy pressure.
  round(waste + dormancy, 4), HALF_EVEN (Python round())."
  [tie]
  (let [cost (num-or tie ":en/monthly-cost-jpy")
        usage (num-or tie ":en/usage-score")
        waste (* cost (- 1.0 (/ (min usage 100.0) 100.0)))
        dormancy (/ (min (num-or tie ":en/last-used-days") 1000.0) 1000.0)]
    (-> (java.math.BigDecimal. (double (+ waste dormancy)))
        (.setScale 4 java.math.RoundingMode/HALF_EVEN)
        double)))

(defn recommend
  "Per-tie recommendation string (:keep / :review / :sever)."
  [tie]
  (let [cost (num-or tie ":en/monthly-cost-jpy")
        usage (num-or tie ":en/usage-score")
        last (num-or tie ":en/last-used-days")
        kind (get tie ":en/kind")]
    (cond
      (and (= kind ":recurring-charge") (== usage 0))
      (if (== cost 0) ":review" ":sever")            ; unrecognized live charge
      (> cost 0)                                      ; paid tie → disclosed organizer thresholds
      (cond
        (and (< usage SEVER-USAGE) (> cost SEVER-COST-JPY)) ":sever"
        (< usage REVIEW-USAGE) ":review"
        :else ":keep")
      ;; cost-free account → dormancy rule (退会候補)
      (>= last DORMANT-SEVER-DAYS) ":sever"
      (>= last DORMANT-REVIEW-DAYS) ":review"
      :else ":keep")))

(defn analyze
  "Per-tie readout (transient — G2): burden, recommendation, cascade-guard.

  A :sever on a service with dependents is DOWNGRADED to :review-cascade — the dependency
  must be re-homed first (依存 detection); kaiyaku never auto-severs a tie other ties stand on.

  Returns {\"ties\" [tie-map…] \"total_monthly_jpy\" d \"recoverable_monthly_jpy\" d
           \"counts\" ordered-map}. `counts` carries ::order = sorted-key order so the
  rendered repr matches dict(sorted(by_rec.items()))."
  [nodes edges]
  (let [deps (dependents edges)
        ties (reduce
              (fn [acc e]
                (if-not (contains? member-tie-kinds (get e ":en/kind"))
                  acc
                  (let [to (get e ":en/to")
                        svc (get nodes to {})
                        rec0 (recommend e)
                        deps-list (vec (sort (get deps to [])))
                        rec (if (and (= rec0 ":sever") (seq deps-list)) ":review-cascade" rec0)]
                    (conj acc
                          {"member" (get e ":en/from")
                           "svc" to
                           "svc_label" (get svc ":svc/label" to)
                           "kind" (get e ":en/kind")
                           "monthly_cost_jpy" (num-or e ":en/monthly-cost-jpy")
                           "usage_score" (num-or e ":en/usage-score")
                           "last_used_days" (num-or e ":en/last-used-days")
                           "burden" (burden e)
                           "recommendation" rec
                           "dependents" deps-list
                           "notice_days" (get svc ":svc/notice-days" 0)
                           "penalty_jpy" (get svc ":svc/penalty-jpy" 0)}))))
              []
              edges)
        ;; ties.sort(key=lambda t: (-t["burden"], t["svc"]))  — Timsort is stable
        ties (vec (sort-by (fn [t] [(- (get t "burden")) (get t "svc")]) ties))
        total (reduce + 0.0 (map #(get % "monthly_cost_jpy") ties))
        recoverable (reduce + 0.0 (map #(get % "monthly_cost_jpy")
                                       (filter #(= ":sever" (get % "recommendation")) ties)))
        by-rec (reduce (fn [m t] (update m (get t "recommendation") (fnil inc 0))) {} ties)
        counts (let [ks (sort (keys by-rec))]
                 (with-meta (reduce (fn [m k] (assoc m k (get by-rec k))) {} ks)
                   {::order (vec ks)}))]
    {"ties" ties
     "total_monthly_jpy" (-> (java.math.BigDecimal. (double total)) (.setScale 2 java.math.RoundingMode/HALF_EVEN) double)
     "recoverable_monthly_jpy" (-> (java.math.BigDecimal. (double recoverable)) (.setScale 2 java.math.RoundingMode/HALF_EVEN) double)
     "counts" counts}))

;; ── report rendering (matches report's f-strings) ────────────────────────────

(defn- comma-int
  "Python f'{n:,}' over an integer (group digits with commas)."
  [n]
  (let [s (str (long n))
        neg (str/starts-with? s "-")
        digits (if neg (subs s 1) s)
        grouped (->> (vec digits) reverse (partition-all 3)
                     (map #(apply str (reverse %))) reverse (str/join ","))]
    (str (when neg "-") grouped)))

(defn- exact-bd
  "BigDecimal of the EXACT binary value of a double — matches Python float formatting
  (Python rounds the true IEEE-754 value, e.g. 7920.15 → 7920.1499…). NOT clojure.core/bigdec,
  which goes through Double.toString (the shortest decimal) and would round 7920.15 → 7920.2."
  ^java.math.BigDecimal [v]
  (java.math.BigDecimal. (double v)))

(defn- fmt-comma-0f
  "Python f'{v:,.0f}' — round to integer (HALF_EVEN), then group with commas."
  [v]
  (let [n (-> (exact-bd v) (.setScale 0 java.math.RoundingMode/HALF_EVEN) .longValueExact)]
    (comma-int n)))

(defn- fmt-0f
  "Python f'{v:.0f}' — round to integer (HALF_EVEN), no grouping."
  [v]
  (str (-> (exact-bd v) (.setScale 0 java.math.RoundingMode/HALF_EVEN) .longValueExact)))

(defn- fmt-1f
  "Python f'{v:.1f}'."
  [v]
  (-> (exact-bd v) (.setScale 1 java.math.RoundingMode/HALF_EVEN) .toPlainString))

(defn- counts-repr
  "Python repr of the counts dict: {':keep': 2, ':review': 1, …}, keys in ::order."
  [counts]
  (let [order (::order (meta counts))
        ks (if order order (keys counts))]
    (str "{"
         (str/join ", " (map (fn [k] (str "'" k "': " (get counts k))) ks))
         "}")))

(defn report
  "Render the enkiri readout markdown (1:1 with report)."
  [res]
  (let [ties (get res "ties")
        L (transient [])]
    (conj! L "# kaiyaku 縁切り readout (transient — computed on read, G2)")
    (conj! L "")
    (conj! L (str "- ties: " (count ties)
                  " · total ¥" (fmt-comma-0f (get res "total_monthly_jpy")) "/mo · "
                  "recoverable ¥" (fmt-comma-0f (get res "recoverable_monthly_jpy")) "/mo"))
    (conj! L (str "- counts: " (counts-repr (get res "counts"))))
    (conj! L "")
    (conj! L "| svc | kind | ¥/mo | usage | burden | recommendation | cost-of-severance |")
    (conj! L "|---|---|---|---|---|---|---|")
    (doseq [t ties]
      (let [notice (get t "notice_days")
            penalty (get t "penalty_jpy")
            sev (if (or (and (number? notice) (not (zero? notice)))
                        (and (number? penalty) (not (zero? penalty)))
                        (and (not (number? notice)) notice)
                        (and (not (number? penalty)) penalty))
                  (str "notice " notice "d / penalty ¥" (comma-int penalty))
                  "—")
            deps (get t "dependents")
            dep-str (if (seq deps) (str " (deps: " (str/join ", " deps) ")") "")]
        (conj! L (str "| " (get t "svc_label") " | " (get t "kind") " | "
                      (fmt-comma-0f (get t "monthly_cost_jpy")) " "
                      "| " (fmt-0f (get t "usage_score")) " | " (fmt-1f (get t "burden")) " | "
                      (get t "recommendation") dep-str " | " sev " |"))))
    (conj! L "")
    (conj! L (str "severance is PLANNED only (plan.py); execution is member-sig + dry-run + "
                  "Council-gated (G5/G6)."))
    (str (str/join "\n" (persistent! L)) "\n")))

#?(:clj
   (defn -main
     "CLI entry: analyze a seed EDN ledger → out/enkiri-readout.md (file I/O at the edge)."
     [& argv]
     (let [argv (vec argv)
           here (delay (-> *file* clojure.java.io/file .getParentFile .getParentFile))
           seed (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                  (clojure.java.io/file (first argv))
                  (clojure.java.io/file @here "data" "seed-en-ledger.kotoba.edn"))
           outdir (if (some #{"--out"} argv)
                    (clojure.java.io/file (nth argv (inc (.indexOf argv "--out"))))
                    (clojure.java.io/file @here "out"))
           {:keys [nodes edges]} (load-file* seed)
           res (analyze nodes edges)]
       (.mkdirs outdir)
       (spit (clojure.java.io/file outdir "enkiri-readout.md") (report res))
       (println (str "kaiyaku: " (count (get res "ties")) " ties · recoverable ¥"
                     (fmt-comma-0f (get res "recoverable_monthly_jpy")) "/mo → "
                     (clojure.java.io/file outdir "enkiri-readout.md")))
       0)))
