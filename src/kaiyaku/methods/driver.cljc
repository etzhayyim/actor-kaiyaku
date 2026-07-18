#!/usr/bin/env bb
;; kaiyaku 解約 — R1 severance DRIVER: capability-gated dispatch (authorize, never execute).
(ns kaiyaku.methods.driver
  "driver.cljc — kaiyaku 解約 R1 severance driver (ADR-2606112201 R1). The
  missing last leg between a member-approved dry-run plan and an actual
  cancellation — implemented as an AUTHORIZATION boundary, NOT live I/O.

  Closes the R0 design gap (capability verification + tier dispatch +
  exactly-once + cascade ordering) WITHOUT wiring a network driver: even when a
  plan is fully authorized this namespace returns an authorization DESCRIPTOR
  with \"executed\" false. The actual T1-API / T2-browser / T3-handoff driver is
  a separate, post-R1 component (mirrors karakuri adapter_live's
  authorize-never-execute membrane, ADR-2606039200, and the fuchi live_gate
  pattern, ADR-2606052300).

  Four invariants, each tested:

    G3 no-server-key — `dispatch` requires a member-presented capability bundle
      (cap.cljc). Absent/expired/wrong-graph/not-approved → REFUSED (returns a
      :refused descriptor; the batch never throws so one bad tie can't abort the
      rest). The server holds no key and never signs.

    G5 member-approval-in-the-leash — a tie is severable ONLY if it is in the
      capability's `approved` allowlist (the exact set the member signed at the
      human-in-the-loop interrupt). cap/usable? enforces it.

    cascade ordering (依存) — a :review-cascade plan is REFUSED for live
      dispatch (its dependents must be re-homed first; kaiyaku never severs a tie
      others stand on). For a :sever plan that still carries rehome-dependency
      steps, those steps MUST precede the irreversible cancel step — asserted
      structurally (assert-cascade-order).

    exactly-once (冪等) — `dispatch-batch` threads an `already-severed` cursor;
      a svc already severed in this (or a resumed) run is a NO-OP
      (:already-severed). Re-running the batch is safe — no double cancellation.

  T3 self-submit is NEVER sent by the driver: the descriptor says the MEMBER
  submits it themselves (the toritsugi/kurashimori default-self-submit pattern).

  Deterministic: `:now-epoch` is supplied by the caller (no wall clock here).
  Pure fns; no network I/O anywhere in this namespace. Portable .cljc."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [kaiyaku.methods.cap :as cap]))

;; ── cascade ordering (依存) ─────────────────────────────────────────────────

(def ^:private cancel-verbs
  "The irreversible verbs — the rehome-dependency steps must precede these."
  #{"api-cancel" "browser-cancel"})

(defn assert-cascade-order
  "Structural guarantee: in a plan's step list, every rehome-dependency step
  precedes the (irreversible) cancel step. Raises if a cancel would run before a
  dependency is re-homed. Returns the plan unchanged on success."
  [plan]
  (let [verbs (mapv #(get % "verb") (get plan "steps"))
        cancel-idx (->> verbs (keep-indexed (fn [i v] (when (cancel-verbs v) i))) first)
        rehome-idxs (->> verbs (keep-indexed (fn [i v] (when (= v "rehome-dependency") i))))]
    (when (and cancel-idx (some #(> % cancel-idx) rehome-idxs))
      (throw (ex-info (str "cascade-order violation on " (get plan "svc")
                           ": a dependency would be re-homed AFTER cancellation — "
                           "kaiyaku never severs a tie others stand on")
                      {:gate :cascade :svc (get plan "svc")})))
    plan))

;; ── the descriptor (authorize, never execute) ───────────────────────────────

(defn- refused [svc why]
  (array-map "svc" svc "authorized" false "status" ":refused"
             "executed" false "server_signed" false "why" why))

(defn- authorized-descriptor [plan reason]
  (let [catalog (get plan "catalog")              ; present iff catalog/enrich-plan ran
        drift (get catalog "g8_drift")]
    (cond-> (array-map
             "svc" (get plan "svc")
             "svc_label" (get plan "svc_label")
             "tier" (get plan "tier")
             "recommendation" (get plan "recommendation")
             "authorized" true
             ;; the membrane authorizes; a post-R1 driver executes. Still no I/O here.
             "executed" false
             "status" (if (= "T3" (get plan "tier")) ":member-submits" ":authorized-dry-run")
             "authorized_by" "member"        ; G3 — never the server
             "server_signed" false           ; G3 — never
             "steps" (get plan "steps")
             ;; G8 — cost-of-severance carried into the authorization, never planned around
             "notice_days" (get plan "notice_days")
             "penalty_jpy" (get plan "penalty_jpy")
             "note" reason)
      ;; surface the disclosed real procedure (catalog/enrich-plan) to the member
      catalog (assoc "disclosed_procedure" catalog)
      ;; G6 honesty — a representative (operator-unverified) procedure is flagged even
      ;; when authorized: the post-R1 driver must operator-verify before touching the service
      (and catalog (false? (get catalog "operator_verified")))
      (assoc "operator_verification_required" true)
      ;; G8 — a cost-of-severance discrepancy between ledger and catalog needs the
      ;; member's explicit acknowledgment; kaiyaku never reconciles it silently
      (seq drift) (assoc "g8_ack_required" true "g8_drift" (vec drift)))))

;; ── dispatch one plan ───────────────────────────────────────────────────────

(defn dispatch
  "Authorize (never execute) the severance of one member-approved plan.

  opts:
    :bundle         member-presented capability bundle (cap.cljc) | nil
    :now-epoch      caller-supplied epoch seconds (deterministic; the leash check)
    :already-severed set of svc-ids already severed this run (exactly-once cursor)

  Returns a descriptor map. Never throws on a gate failure (returns :refused) so
  a batch can't be aborted by one bad tie; the only throw is the structural
  cascade-order assertion (a programming error in the plan, not a runtime gate)."
  [plan {:keys [bundle now-epoch already-severed] :or {already-severed #{}}}]
  (let [svc (get plan "svc")]
    (cond
      ;; exactly-once (冪等) — already severed → NO-OP
      (contains? already-severed svc)
      (assoc (refused svc "already severed this run (exactly-once no-op)")
             "status" ":already-severed")

      ;; cascade — a :review-cascade plan must rehome dependents first, never live-dispatch
      (= ":review-cascade" (get plan "recommendation"))
      (refused svc (str "cascade: dependents must be re-homed BEFORE severance "
                        "(:review-cascade is never live-dispatched; rehome → re-analyze → :sever)"))

      :else
      (let [[ok? why] (cap/usable? bundle {:now-epoch now-epoch :svc-id svc})]
        (if-not ok?
          (refused svc why)
          ;; authorized — assert cascade order structurally, then return descriptor
          (do (assert-cascade-order plan)
              (authorized-descriptor
               plan (str "capability presented (" why "); live driver is a post-R1 component"))))))))

;; ── dispatch a batch (exactly-once cursor threaded) ─────────────────────────

(defn dispatch-batch
  "Authorize a batch of plans, threading the exactly-once cursor. An authorized
  :sever advances the cursor so a re-run (or resume) of the same batch is a
  no-op. Returns {:results [descriptor …] :severed #{svc-ids authorized this run}}.

  Resume-safe: pass the prior run's :severed set as :already-severed to continue
  a partially-completed batch without re-severing anything."
  [plans {:keys [bundle now-epoch already-severed] :or {already-severed #{}}}]
  (loop [ps plans, severed already-severed, out []]
    (if-let [p (first ps)]
      (let [d (dispatch p {:bundle bundle :now-epoch now-epoch :already-severed severed})
            severed' (if (and (get d "authorized")
                              (= ":authorized-dry-run" (get d "status")))
                       (conj severed (get d "svc"))
                       severed)]
        (recur (rest ps) severed' (conj out d)))
      {:results out
       :severed (set/difference severed already-severed)})))

;; ── report ──────────────────────────────────────────────────────────────────

(defn report
  "Render the dispatch authorization summary (human-readable, dry-run honest)."
  [{:keys [results]}]
  (let [L (transient ["# kaiyaku R1 severance dispatch (authorize-only — live driver post-R1)" ""])]
    (doseq [d results]
      (conj! L (str "## " (or (get d "svc_label") (get d "svc"))
                    " — " (get d "status")
                    (when (get d "tier") (str " [" (get d "tier") "]"))))
      (conj! L (str "- authorized: " (get d "authorized")
                    " · executed: " (get d "executed")
                    " · server_signed: " (get d "server_signed")))
      (conj! L (str "- " (or (get d "note") (get d "why"))))
      (conj! L ""))
    (str (str/join "\n" (persistent! L)) "\n")))
