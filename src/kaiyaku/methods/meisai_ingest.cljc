(ns kaiyaku.methods.meisai-ingest
  "kaiyaku 解約 — meisai 明細 handoff ingest (closes the meisai → kaiyaku round-trip, ADR-2606122400
  + 2606112201). Sibling of handoff-ingest (which ingests tate 盾's clause handoff); this one
  ingests meisai's recurring-charge handoff (data/kaiyaku-handoff.edn from meisai/recurring.cljc).

  meisai SURFACES a recurring card charge (a merchant billed across ≥N months); kaiyaku INGESTS it
  as a 縁-ledger tie and DECIDES keep/review/sever via its own analyze (G2 edge-primary burden +
  member-sig + dry-run gates). A meisai recurring charge maps onto the tie kaiyaku already models —
  `:en/kind :recurring-charge` over a `:svc/kind :card-merchant` node (the 'Unknown Card Merchant'
  shape in the seed) — so analyze/plan consume it with NO new decision logic.

  CONSTITUTIONAL:
    G1/G3 — the INPUT (meisai handoff) and the produced ledger forms are the member's OWN PERSONAL
      data (they reveal subscriptions): the -main output goes to gitignored out/, never committed/
      pinned/posted. The pure fns operate on data only and are tested on a SYNTHETIC fixture.
    N1 — a tie target is a SERVICE (`:svc/kind :card-merchant`), never a person. Enforced by
      construction (the node kind is fixed) + test.
    G8 — meisai's amount is carried as the JPY monthly cost (cost-of-severance honesty downstream);
      a non-JPY charge (no FX here) lands cost 0 → analyze routes it to :review, never auto-:sever.

  Reuses kaiyaku.methods.analyze/read-edn (kaiyaku's own EDN reader — keys stay ':…' STRINGS).
  Pure fns; file I/O behind #?(:clj …). Portable .cljc, repo clj/bb rule."
  (:require [clojure.string :as str]
            [kaiyaku.methods.analyze :as analyze]
            #?(:clj [clojure.java.io :as io])))

(defn- sha256-hex [^String s]
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) b))))

(defn- slugify
  "Merchant string → readable ascii id fragment ('AMAZON.CO.JP' → 'amazon-co-jp'); blank if the
  name is all non-ascii (then the hash suffix carries identity)."
  [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+|-+$)" "")))

(defn svc-id
  "Deterministic, collision-safe service id for a meisai merchant (stable across re-ingest →
  idempotent ledger merge): 'svc:meisai:<slug>-<hash6>' (slug omitted when blank)."
  [merchant]
  (let [slug (slugify merchant)
        h (subs (sha256-hex (str merchant)) 0 6)]
    (str "svc:meisai:" (when (seq slug) (str slug "-")) h)))

(defn meisai?
  "Is this a meisai recurring-charge handoff record (vs a tate clause handoff)?"
  [h]
  (and (map? h) (= ":meisai" (get h ":handoff/source")) (= true (get h ":handoff/recurring"))))

(defn ingest
  "Parse meisai's handoff EDN → the meisai recurring-charge records (drops anything else)."
  [handoff-text]
  (filterv meisai? (analyze/read-edn handoff-text)))

(defn candidate->forms
  "One meisai handoff record → [svc-node en-tie] 縁-ledger forms (':…' STRING keys, exactly the
  shape analyze/load-graph consumes). JPY amount (minor units == yen) becomes the JPY monthly cost;
  a non-JPY charge lands cost 0 (no FX) so analyze routes it to :review, not auto-:sever."
  [member-id h]
  (let [merchant (str (get h ":handoff/merchant" (get h ":handoff/svc" "?")))
        currency (str (get h ":handoff/currency" ":jpy"))
        typical (long (get h ":handoff/typical-amount" 0))
        ;; report-time JPY-equivalent from meisai's fx leg (advisory); prices a foreign charge so
        ;; analyze can recommend on it. Absent → cost 0 → analyze routes to :review (G8 honesty).
        jpy-equiv (get h ":handoff/jpy-equivalent")
        months (or (get h ":handoff/months") [])
        occ (long (get h ":handoff/occurrences" (count months)))
        jpy? (= currency ":jpy")
        cost-jpy (cond jpy? typical
                       (some? jpy-equiv) (long jpy-equiv)
                       :else 0)
        sid (svc-id merchant)]
    [{":svc/id" sid
      ":svc/label" merchant
      ":svc/kind" ":card-merchant"
      ":svc/category" "card-recurring"
      ":svc/sourcing" ":member-card"
      ":svc/cancel" {":api" ":none" ":browser" ":unknown" ":self-submit" true}
      ":svc/notice-days" 0
      ":svc/penalty-jpy" 0}
     {":en/from" member-id
      ":en/to" sid
      ":en/kind" ":recurring-charge"
      ":en/monthly-cost-jpy" cost-jpy
      ":en/usage-score" 0
      ":en/last-used-days" 0
      ":en/first-seen" (str (first months))
      ":en/sourcing" ":member-card"
      ;; provenance from meisai (analyze ignores unknown attrs; the member can audit)
      ":en/meisai-occurrences" occ
      ":en/meisai-currency" currency
      ":en/meisai-typical-amount" typical
      ":en/meisai-fx-priced" (and (not jpy?) (some? jpy-equiv))
      ":en/meisai-advisory" true}]))

(defn to-ledger-forms
  "All meisai recurring candidates → 縁-ledger forms appendable to the member's ledger.
  Deterministic (sorted by svc id)."
  ([cands] (to-ledger-forms cands "member:self"))
  ([cands member-id]
   (->> cands
        (mapcat #(candidate->forms member-id %))
        (sort-by #(or (get % ":svc/id") (get % ":en/to")))
        vec)))

;; ── EDN rendering (real-keyword wire format, same as the seed ledger) ─────────
(defn- render [v]
  (cond
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)
    (float? v) (str (double v))
    (and (string? v) (str/starts-with? v ":")) v          ; ':…' string → bare keyword
    (string? v) (str \" (str/replace (str/replace v "\\" "\\\\") "\"" "\\\"") \")
    (sequential? v) (str "[" (str/join " " (map render v)) "]")
    (map? v) (str "{" (str/join " " (map (fn [[k val]] (str (render k) " " (render val))) v)) "}")
    (nil? v) "nil"
    :else (str v)))

(defn forms->edn
  "Render ledger forms as an EDN list (real-keyword syntax; re-readable by analyze/read-edn)."
  [forms]
  (str ";; kaiyaku 縁-ledger fragment — meisai 明細 recurring-charge ties. GENERATED, do not hand-edit.\n"
       ";; PERSONAL data (reveals the member's subscriptions): never committed/pinned (G1/G3).\n"
       ";; analyze decides keep/review/sever; severance stays member-sig + dry-run gated (G5/G6).\n"
       "[" (str/join "\n " (map render forms)) "]\n"))

#?(:clj
   (do
     (def ^:private here (-> (io/file *file*) .getParentFile .getParentFile))

     (defn -main
       "Read meisai's handoff EDN → kaiyaku 縁-ledger fragment under gitignored out/."
       [& argv]
       (let [argv (vec argv)
             opt (fn [f d] (let [i (.indexOf argv f)]
                             (if (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)) d)))
             in-path (opt "--in" (str (io/file here ".." "meisai" "data" "kaiyaku-handoff.edn")))
             member (opt "--member" "member:self")
             outdir (io/file (opt "--out" (str (io/file here "out"))))
             cands (if (.exists (io/file in-path)) (ingest (slurp in-path)) [])
             forms (to-ledger-forms cands member)]
         (.mkdirs outdir)
         (spit (io/file outdir "meisai-ledger-fragment.kotoba.edn") (forms->edn forms))
         (println (str "kaiyaku: ingested " (count cands) " meisai recurring-charge candidate(s) → "
                       (count forms) " ledger forms → " (io/file outdir "meisai-ledger-fragment.kotoba.edn")
                       " (analyze decides keep/review/sever; severance member-sig + dry-run gated)"))
         0))))
