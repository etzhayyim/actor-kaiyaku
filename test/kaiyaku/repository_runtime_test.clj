(ns kaiyaku.repository-runtime-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kaiyaku.repository-runtime :as runtime]
            [langchain.db :as db]
            [langchain.edn-persist :as edn-persist]
            [langgraph.checkpoint :as checkpoint]))

(deftest checkpoint-is-restored-after-runtime-recreation
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "kaiyaku-repository-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "state.edn")
        environment {"KOTOBA_REPOSITORY_STATE_FILE" (.getPath file)
                     "KOTOBA_REPOSITORY_STREAM" runtime/default-stream}
        build (fn []
                (checkpoint/datomic-checkpointer
                 (db/create-conn
                  checkpoint/checkpoint-schema
                  (edn-persist/configured-persist
                   environment runtime/default-stream))))
        first-runtime (build)]
    (checkpoint/put! first-runtime "member-session"
                     {:step 0 :state {:plans [:review]}
                      :frontier [:approve] :status :interrupted})
    (is (= {:step 0 :state {:plans [:review]}
            :frontier [:approve] :status :interrupted}
           (checkpoint/get-latest (build) "member-session")))
    (is (.isFile file))))
