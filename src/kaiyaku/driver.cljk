(ns kaiyaku.driver
  "kaiyaku 解約 — capability-gated severance DRIVER for the clj/ langgraph lane
  (ADR-2606112201 R1, clj-native sibling of methods/driver.cljc).

  Authorize, NEVER execute: `dispatch` verifies the member capability and returns
  an authorization descriptor with `:executed false`. The actual T1/T2/T3 driver is
  a separate post-R1 component (the agent's :rehearse node only ever runs DRY-RUN
  rehearsals over injected mock surfaces). Same four invariants as the methods/
  lane, on the clj/ lane's real-keyword plan maps:

    G3 no-server-key — absent/expired/wrong-scope capability → :refused (never throws
      on a gate; a batch can't be aborted by one bad tie).
    G5 in-the-leash — a tie is severable only if in the capability `:approved` list.
    cascade (依存) — a :review-cascade plan is never live-dispatched; a :sever plan's
      rehome-dependency steps must precede the irreversible cancel step.
    exactly-once (冪等) — dispatch-batch threads an already-severed cursor."
  (:require [clojure.set :as set]
            [kaiyaku.cap :as cap]))

(def ^:private cancel-verbs #{"api-cancel" "browser-cancel"})

(defn assert-cascade-order
  "Every rehome-dependency step must precede the (irreversible) cancel step."
  [plan]
  (let [verbs (mapv :verb (:steps plan))
        cancel-idx (->> verbs (keep-indexed (fn [i v] (when (cancel-verbs v) i))) first)
        rehome-idxs (->> verbs (keep-indexed (fn [i v] (when (= v "rehome-dependency") i))))]
    (when (and cancel-idx (some #(> % cancel-idx) rehome-idxs))
      (throw (ex-info (str "cascade-order violation on " (:svc plan)
                           ": a dependency would be re-homed AFTER cancellation")
                      {:gate :cascade :svc (:svc plan)})))
    plan))

(defn- refused [svc why]
  {:svc svc :authorized false :status :refused :executed false :server-signed false :why why})

(defn- authorized-descriptor [plan reason]
  (let [catalog (:catalog plan)              ; present iff catalog/enrich-plan ran
        drift (:g8-drift catalog)]
    (cond-> {:svc (:svc plan)
             :svc-label (:svc-label plan)
             :tier (:tier plan)
             :recommendation (:recommendation plan)
             :authorized true
             :executed false                  ; the membrane authorizes; a post-R1 driver executes
             :status (if (= "T3" (:tier plan)) :member-submits :authorized-dry-run)
             :authorized-by "member"          ; G3 — never the server
             :server-signed false             ; G3 — never
             :steps (:steps plan)
             :notice-days (:notice-days plan)  ; G8
             :penalty-jpy (:penalty-jpy plan)  ; G8
             :note reason}
      ;; surface the disclosed real procedure (catalog/enrich-plan) to the member
      catalog (assoc :disclosed-procedure catalog)
      ;; G6 honesty — a representative (unverified) procedure is flagged even when authorized
      (and catalog (false? (:operator-verified catalog)))
      (assoc :operator-verification-required true)
      ;; G8 — a ledger↔catalog cost discrepancy needs the member's explicit acknowledgment
      (seq drift) (assoc :g8-ack-required true :g8-drift (vec drift)))))

(defn dispatch
  "Authorize (never execute) the severance of one member-approved plan."
  [plan {:keys [bundle now-epoch already-severed] :or {already-severed #{}}}]
  (let [svc (:svc plan)]
    (cond
      (contains? already-severed svc)
      (assoc (refused svc "already severed this run (exactly-once no-op)")
             :status :already-severed)

      (= :review-cascade (:recommendation plan))
      (refused svc (str "cascade: dependents must be re-homed BEFORE severance "
                        "(:review-cascade is never live-dispatched)"))

      :else
      (let [[ok? why] (cap/usable? bundle {:now-epoch now-epoch :svc-id svc})]
        (if-not ok?
          (refused svc why)
          (do (assert-cascade-order plan)
              (authorized-descriptor
               plan (str "capability presented (" why "); live driver is a post-R1 component"))))))))

(defn dispatch-batch
  "Authorize a batch, threading the exactly-once cursor. Returns
  {:results [descriptor …] :severed #{svc-ids authorized this run}}."
  [plans {:keys [bundle now-epoch already-severed] :or {already-severed #{}}}]
  (loop [ps plans, severed already-severed, out []]
    (if-let [p (first ps)]
      (let [d (dispatch p {:bundle bundle :now-epoch now-epoch :already-severed severed})
            severed' (if (and (:authorized d) (= :authorized-dry-run (:status d)))
                       (conj severed (:svc d))
                       severed)]
        (recur (rest ps) severed' (conj out d)))
      {:results out :severed (set/difference severed already-severed)})))
