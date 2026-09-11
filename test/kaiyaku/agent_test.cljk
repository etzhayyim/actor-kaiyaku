(ns kaiyaku.agent-test
  "End-to-end actor test: ledger → readout → plans → ‖member-sig interrupt‖ →
  T2 browser-use rehearsal on a pure-data mock autopay surface (the
  PayPal-autopay-shaped flow, fully synthetic — G1), plus executor gates
  (G3 tier refusal, G4 Murakumo-only)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.checkpoint :as cp]
            [langchain.model :as model]
            [langchain.message :as msg]
            [langchain.db :as db]
            [browseruse.browser :as bbrowser]
            [browseruse.agent :as bagent]
            [kaiyaku.agent :as agent]
            [kaiyaku.executor :as executor]))

(def seed #?(:clj (slurp "data/seed-en-ledger.kotoba.edn")
             :cljs (throw (ex-info "host must inject the seed" {}))))

;; ── mock rehearsal surface: an autopay-management page shape (synthetic) ──
;; The representative shape of a wallet's "automatic payments" surface
;; (e.g. /myaccount/autopay): list → detail → confirm → cancelled.

(def autopay-site
  {"https://wallet.example/myaccount/autopay"
   {:title "Wallet W — Automatic payments"
    :elements [{:tag "a" :text "Video Streaming A — ¥1,980/mo"
                :nav "https://wallet.example/myaccount/autopay/B-001"}]}
   "https://wallet.example/myaccount/autopay/B-001"
   {:title "Automatic payment B-001 — Video Streaming A"
    :elements [{:tag "button" :text "Cancel automatic payment"
                :nav "https://wallet.example/myaccount/autopay/B-001/confirm"}]}
   "https://wallet.example/myaccount/autopay/B-001/confirm"
   {:title "Confirm cancellation"
    :elements [{:tag "button" :text "Yes, cancel future payments"
                :nav "https://wallet.example/myaccount/autopay/B-001/cancelled"}]}
   "https://wallet.example/myaccount/autopay/B-001/cancelled"
   {:title "Automatic payment cancelled"
    :elements []}})

(defn scripted-model
  "Deterministic T2 sub-agent script: walk list → detail → confirm → done."
  []
  (model/mock-model
   [(msg/ai "" {:tool-calls [{:id "c1" :name "click_element" :input {:index 0}}]})
    (msg/ai "" {:tool-calls [{:id "c2" :name "click_element" :input {:index 0}}]})
    (msg/ai "" {:tool-calls [{:id "c3" :name "click_element" :input {:index 0}}]})
    (msg/ai "" {:tool-calls [{:id "c4" :name "done"
                              :input {:text "Automatic payment cancelled (rehearsal)"
                                      :success true}}]})]))

(defn build []
  (agent/build-actor
   {:model (scripted-model)
    :browser-for (fn [svc-id]
                   (when (= "svc:video-a" svc-id)
                     (bbrowser/mock-browser autopay-site
                                            "https://wallet.example/myaccount/autopay")))
    :checkpointer (cp/mem-checkpointer)}))

(deftest member-sig-interrupt-then-rehearse
  (let [actor (build)
        tid   "kaiyaku-t1"
        out1  (agent/run-until-approval actor seed tid)]
    (testing "phase 1 halts at the member-sig gate (G5) with plans on the checkpoint"
      (is (= :interrupted (:status out1)))
      (is (= [:approve] (:frontier out1)))
      (is (= 6 (count (-> out1 :state :plans))))
      (is (= {:keep 2 :review 1 :review-cascade 1 :sever 5}
             (-> out1 :state :readout :counts)))
      (is (empty? (-> out1 :state :rehearsals))))
    (let [out2 (agent/resume-with-approval
                actor tid ["svc:video-a" "svc:cloud-h" "svc:gym-b"])]
      (testing "phase 2 runs only the member-approved plans"
        (is (= :done (:status out2)))
        (is (= 3 (count (-> out2 :state :plans))))
        (is (= 3 (count (-> out2 :state :rehearsals)))))
      (let [reh (group-by :tier (-> out2 :state :rehearsals))]
        (testing "T2 — browser-use rehearsal completes the mock autopay flow"
          (let [t2 (first (get reh "T2"))]
            (is (= "svc:video-a" (:svc t2)))
            (is (= :browser-use (:engine t2)))
            (is (true? (:done t2)))
            (is (= "Automatic payment cancelled (rehearsal)" (:result t2)))
            (is (= :dry-run (:mode t2)))))
        (testing "T1 — prepared only; live call G6-gated"
          (is (re-find #"G6-gated" (:note (first (get reh "T1"))))))
        (testing "T3 — member self-submits"
          (is (re-find #"MEMBER submits" (:note (first (get reh "T3"))))))))))

(deftest unapproved-plans-never-rehearse
  (let [actor (build)
        tid   "kaiyaku-t2"]
    (agent/run-until-approval actor seed tid)
    (let [out (agent/resume-with-approval actor tid [])]
      (testing "G5 — empty approval ⇒ nothing proceeds"
        (is (= :done (:status out)))
        (is (empty? (-> out :state :plans)))
        (is (empty? (-> out :state :rehearsals)))))))

(def cancel-cap
  "A member-presented CACAO leash approving the three demo svcs (R1)."
  {:cacao-b64 "opaque" :aud "did:web:etzhayyim.com" :capability "service:cancel"
   :graph "graph:kaiyaku" :exp 9999999999 :nonce "n"
   :approved ["svc:video-a" "svc:cloud-h" "svc:gym-b"]})

(deftest dispatch-authorizes-with-capability-never-executes
  (let [actor (build)
        tid   "kaiyaku-cap"]
    (agent/run-until-approval actor seed tid)
    (let [out (agent/resume-with-approval actor tid
                                          ["svc:video-a" "svc:cloud-h" "svc:gym-b"] cancel-cap)
          ds (-> out :state :descriptors)
          by-svc (into {} (map (juxt :svc identity)) ds)]
      (testing "every approved plan gets an authorization descriptor (executed=false, G6)"
        (is (= 3 (count ds)))
        (is (every? #(true? (:authorized %)) ds))
        (is (every? #(false? (:executed %)) ds))
        (is (every? #(false? (:server-signed %)) ds)))
      (testing "tier → status: T1/T2 authorized-dry-run, T3 member-submits"
        (is (= :authorized-dry-run (:status (by-svc "svc:cloud-h"))))   ; T1
        (is (= :authorized-dry-run (:status (by-svc "svc:video-a"))))   ; T2
        (is (= :member-submits (:status (by-svc "svc:gym-b")))))        ; T3
      (testing "rehearsal still runs (dry-run is never gated by the capability)"
        (is (= 3 (count (-> out :state :rehearsals))))))))

(deftest dispatch-refuses-without-capability
  (let [actor (build)
        tid   "kaiyaku-nocap"]
    (agent/run-until-approval actor seed tid)
    (let [out (agent/resume-with-approval actor tid ["svc:video-a" "svc:cloud-h" "svc:gym-b"])
          ds (-> out :state :descriptors)]
      (testing "no capability → every descriptor refused, but rehearsal still proceeds (dry-run)"
        (is (= 3 (count ds)))
        (is (every? #(= :refused (:status %)) ds))
        (is (every? #(false? (:executed %)) ds))
        (is (= 3 (count (-> out :state :rehearsals))))))))

(deftest g5-checkpointer-required
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"G5"
       (agent/build-actor {:model (scripted-model)}))))

(deftest g3-executor-refuses-non-t2
  (testing "executor refuses to rehearse a T3 (stance-refused) plan"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"G3"
         (executor/rehearse-browser!
          {:model (scripted-model)
           :browser (bbrowser/mock-browser autopay-site "https://wallet.example/myaccount/autopay")
           :plan {:svc "svc:gym-b" :svc-label "Gym Membership B" :tier "T3"
                  :notice-days 30 :penalty-jpy 5000}})))))

(deftest g4-murakumo-only
  (testing "non-loopback inference gateways are refused structurally"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"G4"
         (executor/murakumo-model {:url "https://api.openai.com/v1/messages"
                                   :http-fn (fn [_] {:status 200 :body "{}"})
                                   :json-write str :json-read (fn [_] {})})))
    (is (some? (executor/murakumo-model {:http-fn (fn [_] {:status 200 :body "{}"})
                                         :json-write str :json-read (fn [_] {})})))))

(deftest g9-action-datom-audit-trail
  (testing "with a :history-conn every rehearsal action lands as datoms"
    (let [conn (db/create-conn bagent/log-schema)
          out  (executor/rehearse-browser!
                {:model (scripted-model)
                 :browser (bbrowser/mock-browser autopay-site
                                                 "https://wallet.example/myaccount/autopay")
                 :plan {:svc "svc:video-a" :svc-label "Video Streaming A" :tier "T2"
                        :notice-days 0 :penalty-jpy 0}
                 :history-conn conn})
          {:keys [q]} db/api
          names (q '[:find [?n ...] :where [_ :action/name ?n]] (db/db conn))]
      (is (true? (:done out)))
      (is (contains? (set names) "click_element"))
      (is (contains? (set names) "done")))))
