(ns kaiyaku.analyze
  "kaiyaku 解約 — edge-primary tie-burden analyzer (cljc port of methods/analyze.py,
  ADR-2606112201). Numeric parity with the Python implementation.

  CONSTITUTIONAL:
    G2 — edge-primary. The severance decision lives ONLY on the :en/* tie
      (burden = monthly cost × unused fraction + dormancy, computed on READ).
      No per-member score, no score-of-soul, no \"toxic person\" rating
      (反個人主義).
    G8 — honesty: recommendations mirror the DISCLOSED organizer thresholds;
      notice/penalty are surfaced as cost-of-severance, never advised around.")

(def member-tie-kinds #{:subscribes :holds-account :recurring-charge})
(def dependency-kinds #{:depends-on})

;; disclosed organizer thresholds (organizer CLAUDE.md monthly analysis —
;; mirrored, not invented; G8)
(def sever-usage 20)
(def sever-cost-jpy 500)
(def review-usage 50)
;; dormant-account thresholds (cost-free :holds-account ties)
(def dormant-sever-days 365)
(def dormant-review-days 180)

(defn- round-to [places x]
  (let [p (Math/pow 10.0 places)]
    (/ (Math/round (* (double x) p)) p)))

(defn dependents
  "svc-id → [svc-ids that depend on it] (SSO / payment-method cascade inputs)."
  [edges]
  (reduce (fn [m e]
            (if (dependency-kinds (:en/kind e))
              (update m (:en/to e) (fnil conj []) (:en/from e))
              m))
          {} edges))

(defn burden
  "Tie burden, computed on read (G2): paid waste + dormancy pressure."
  [tie]
  (let [cost     (double (or (:en/monthly-cost-jpy tie) 0))
        usage    (double (or (:en/usage-score tie) 0))
        waste    (* cost (- 1.0 (/ (min usage 100.0) 100.0)))
        dormancy (/ (min (double (or (:en/last-used-days tie) 0)) 1000.0) 1000.0)]
    (round-to 4 (+ waste dormancy))))

(defn recommend [tie]
  (let [cost      (double (or (:en/monthly-cost-jpy tie) 0))
        usage     (double (or (:en/usage-score tie) 0))
        last-used (double (or (:en/last-used-days tie) 0))
        kind      (:en/kind tie)]
    (cond
      ;; unrecognized live charge
      (and (= :recurring-charge kind) (zero? usage))
      (if (zero? cost) :review :sever)

      ;; paid tie → disclosed organizer thresholds (G8)
      (pos? cost)
      (cond (and (< usage sever-usage) (> cost sever-cost-jpy)) :sever
            (< usage review-usage) :review
            :else :keep)

      ;; cost-free account → dormancy rule (退会候補)
      (>= last-used dormant-sever-days) :sever
      (>= last-used dormant-review-days) :review
      :else :keep)))

(defn analyze
  "Per-tie readout (transient — G2): burden, recommendation, cascade-guard.

  A :sever on a service with dependents is DOWNGRADED to :review-cascade —
  the dependency must be re-homed first (依存 detection); kaiyaku never
  auto-severs a tie other ties stand on."
  [{:keys [nodes edges]}]
  (let [deps (dependents edges)
        ties (->> edges
                  (filter #(member-tie-kinds (:en/kind %)))
                  (map (fn [e]
                         (let [svc  (get nodes (:en/to e) {})
                               rec  (recommend e)
                               dnts (vec (sort (get deps (:en/to e) [])))
                               rec  (if (and (= :sever rec) (seq dnts))
                                      :review-cascade
                                      rec)]
                           {:member           (:en/from e)
                            :svc              (:en/to e)
                            :svc-label        (get svc :svc/label (:en/to e))
                            :kind             (:en/kind e)
                            :monthly-cost-jpy (double (or (:en/monthly-cost-jpy e) 0))
                            :usage-score      (double (or (:en/usage-score e) 0))
                            :last-used-days   (double (or (:en/last-used-days e) 0))
                            :burden           (burden e)
                            :recommendation   rec
                            :dependents       dnts
                            :notice-days      (get svc :svc/notice-days 0)
                            :penalty-jpy      (get svc :svc/penalty-jpy 0)})))
                  (sort-by (juxt #(- (:burden %)) :svc))
                  vec)]
    {:ties ties
     :total-monthly-jpy       (round-to 2 (reduce + 0.0 (map :monthly-cost-jpy ties)))
     :recoverable-monthly-jpy (round-to 2 (reduce + 0.0 (->> ties
                                                             (filter #(= :sever (:recommendation %)))
                                                             (map :monthly-cost-jpy))))
     :counts (into {} (frequencies (map :recommendation ties)))}))
