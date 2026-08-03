(ns kaiyaku.repository-runtime
  "Repository deployment adapter for the Kaiyaku LangGraph actor.

  Checkpoints remain plaintext and editable in the authenticated user's local
  EDN projection. Cloud Itonami owns encryption and publication; this actor is
  only given the state-file coordinate and its stable stream."
  (:require [langchain.db :as db]
            [langchain.edn-persist :as edn-persist]
            [langgraph.checkpoint :as checkpoint])
  (:gen-class))

(def default-stream "actor/etzhayyim/kaiyaku")

(defn repository-connection
  "Create a replaying Datomic-shaped connection over the injected workspace."
  []
  (db/create-conn checkpoint/checkpoint-schema
                  (edn-persist/required-persist-from-env default-stream)))

(defn repository-checkpointer
  "Create the checkpointer passed to `kaiyaku.agent/build-actor`. Recreating it
  in a new process replays the same user's prior checkpoint transaction log."
  []
  (checkpoint/datomic-checkpointer (repository-connection)))

(defn heartbeat!
  "Small deployment/readiness transaction. It contains no member ledger data."
  [thread-id]
  (let [checkpointer (repository-checkpointer)
        previous (checkpoint/get-latest checkpointer thread-id)
        next-step (inc (long (or (:step previous) -1)))
        value {:step next-step :state {:runtime :ready}
               :frontier [] :status :ready}]
    (checkpoint/put! checkpointer thread-id value)
    {:thread/id thread-id :step next-step :restored? (some? previous)}))

(defn -main [& [command thread-id]]
  (case command
    "heartbeat" (prn (heartbeat! (or thread-id "deployment-readiness")))
    (throw (ex-info "usage: clojure -M:repository heartbeat [thread-id]"
                    {:type :kaiyaku.repository/invalid-command}))))
