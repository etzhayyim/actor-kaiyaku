;; mesh.clj — kaiyaku 解約 KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:kaiyaku (縁切り / tie-severance executor). Service
;; mesh pattern (SERVICE-MESH-PATTERN.md): request-driven, :on-http trigger. The
;; on-http handler records the member's own service ties and returns a dry-run
;; severance PLAN via Datalog. The full karakuri-tier execution stays in methods.
;;
;; Posture: returns a dry-run PLAN only; destructive severance = member-sig + Council
;; (mesh component performs NO outward action); cost-of-severance honesty; human
;; relationships out of scope (→ kokoro).
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns kaiyaku)

(defn handle []
  ;; record the member's ties + dry-run severance routing; return the plan.
  (kqe-assert! "kaiyaku" "subscription" "sever-plan" "cancel-via-api")
  (kqe-assert! "kaiyaku" "dormant-account" "sever-plan" "review-cascade")
  (kqe-query "plan(?p) :- sever-plan(?p)."))

(defn run [ctx] (handle))
(defn on-http [req] (handle))
