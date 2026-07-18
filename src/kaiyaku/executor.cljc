(ns kaiyaku.executor
  "kaiyaku 解約 — T2 severance REHEARSAL engines over browser-use-clj /
  computer-use-clj (ADR-2606112201 + ADR-2606039200 karakuri tiering).

  R0: REHEARSAL ONLY. The host injects the surface (an IBrowser / IComputer);
  the committed code paths run against pure-data mocks — a dry-run 稽古 of the
  cancellation flow, every step logged as datoms (browseruse/computeruse
  :history-conn). Pointing these at a LIVE browser/desktop session is the
  G6-gated live leg (Council Lv6+ + operator + member-sig) and is NOT wired
  here — kaiyaku.plan/execute throws.

  G3 is structural twice over:
    1. rehearse-* refuses any plan that is not tier T2 — the ToS stance gate
       upstream in kaiyaku.plan/select-tier (:prohibited/:unknown never reach
       T2).
    2. The action registry handed to the sub-agent is the library default set
       (navigate / click / input / scroll / back · screenshot / key / type) —
       no evasion tool EXISTS to call (evasion verbs are unrepresentable).

  G4/ADR-2605215000: murakumo-model refuses any non-loopback gateway."
  (:require [clojure.string :as str]
            [browseruse.agent :as bagent]
            [computeruse.agent :as cagent]
            [langchain.model :as model]))

(defn rehearsal-task
  "Task prompt for the T2 sub-agent. The surface is a REHEARSAL copy; the
  disclosed cost-of-severance (G8) rides along, and the agent is told to stop
  honestly rather than work around any obstacle (G3)."
  [plan]
  (str "DRY-RUN rehearsal on a REHEARSAL COPY of the service surface — never a "
       "live account. Walk the cancellation (解約) flow of " (:svc-label plan)
       " (" (:svc plan) ") on the member's own-account surface to the final "
       "confirmation, then call done with the confirmation text. "
       "Disclosed cost-of-severance: notice " (:notice-days plan) "d, penalty ¥"
       (:penalty-jpy plan) " — surface it, never plan around it. "
       "If the surface blocks you, call done with success=false; do not work "
       "around any obstacle."))

(defn- require-t2! [plan engine]
  (when-not (= "T2" (:tier plan))
    (throw (ex-info (str "G3: only a T2 (ToS-permitted stance) plan may be rehearsed via "
                         engine " — tier " (:tier plan) " refused")
                    {:gate :g3 :tier (:tier plan) :svc (:svc plan)}))))

(defn rehearse-browser!
  "T2 rehearsal via browser-use-clj over an INJECTED IBrowser (R0: mock only).
  → {:svc .. :tier \"T2\" :mode :dry-run :engine :browser-use
     :done bool :result .. :steps n}"
  [{:keys [model browser plan history-conn session-id max-steps]
    :or {max-steps 12}}]
  (require-t2! plan "browser-use")
  (-> (bagent/run {:model model
                   :browser browser
                   :task (rehearsal-task plan)
                   :history-conn history-conn
                   :session-id (or session-id (str "kaiyaku:" (:svc plan)))
                   :max-steps max-steps})
      (select-keys [:result :done :steps])
      (assoc :svc (:svc plan) :tier "T2" :mode :dry-run :engine :browser-use)))

(defn rehearse-desktop!
  "T2 rehearsal via computer-use-clj over an INJECTED IComputer — for services
  whose own-account cancellation surface is a desktop app, not the web
  (tedai 手代 sibling surface). Same gates as rehearse-browser!."
  [{:keys [model computer plan display history-conn session-id max-steps]
    :or {max-steps 12}}]
  (require-t2! plan "computer-use")
  (-> (cagent/run {:model model
                   :computer computer
                   :display display
                   :task (rehearsal-task plan)
                   :history-conn history-conn
                   :session-id (or session-id (str "kaiyaku:" (:svc plan)))
                   :max-steps max-steps})
      (select-keys [:result :done :steps])
      (assoc :svc (:svc plan) :tier "T2" :mode :dry-run :engine :computer-use)))

;; ───────────────────────── Murakumo-only model (G4) ─────────────────────────

(def murakumo-gateway
  "The sole inference SSoT (ADR-2605215000): loopback LiteLLM."
  "http://127.0.0.1:4000/v1/messages")

(defn murakumo-model
  "Anthropic-shaped ChatModel pointed at the loopback Murakumo LiteLLM
  gateway. Any non-loopback URL is refused structurally (G4 /
  ADR-2605215000 — no commercial GPU / no vendor-direct inference path).

    (murakumo-model {:model \"gemma3:4b\" :http-fn host-fetch
                     :json-write … :json-read …})"
  [{:keys [url] :or {url murakumo-gateway} :as opts}]
  (when-not (str/starts-with? url "http://127.0.0.1:4000/")
    (throw (ex-info "G4/ADR-2605215000: Murakumo-only — non-loopback inference gateway refused"
                    {:gate :g4 :url url})))
  (model/anthropic-model (merge {:api-key "murakumo"} opts {:url url})))
