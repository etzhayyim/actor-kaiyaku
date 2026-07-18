# kaiyaku 解約 — R1 MATURITY (GENERATED — do not hand-edit; see methods/maturity.cljc)

ADR-2606112201 R1. The driver AUTHORIZES; a post-R1 component executes (G6) — there is
NO live cancellation I/O in this codebase (`plan/execute` raises). Dry-run throughout.

## R1 execution-leg components

- [x] **audit** — G9 READ side: fold the receipt log back into entity views + query; standing G6 verification (no receipt ever records a live execution: executed=0, server-signed=0)
- [x] **capability** — member-presented CACAO; `approved` svc-id allowlist = G5 in the leash; present-only — kaiyaku never signs (no-server-key, ADR-2605231525)
- [x] **catalog** — real-service 解約 procedures; operator-verified=false (:representative, G6); derive-tier ≡ plan/select-tier (no data↔logic drift)
- [x] **driver** — authorize-never-execute membrane; cascade ordering + exactly-once; executed=false ALWAYS (G6); surfaces disclosed procedure + g8_drift to the member
- [x] **handoff** — plan → karakuri com.etzhayyim.karakuri.serviceOp; validated vs karakuri's OWN lexicon (tier-scheme + enum parity, no-drift)
- [x] **pipeline** — analyze→plan→enrich→dispatch→serviceop→receipt end-to-end, dry-run (integration-tested)
- [x] **receipt** — catalog + authorization-receipt datoms → kotoba commit-DAG (G9 audit, verify-chain); a credential is NEVER stored (refused at emit)

## Cancellation-procedure catalog (:representative)

- services: 32
- tier mix: T1 1, T3 31
- region: global 25, jp 7
- category coverage: 15/15 (100.0%)
- **operator-verified: 0 / 32** (G6: every entry must be operator-verified before live use)

## Gates

- **G1** member-principal-own-ties-only
- **G2** edge-primary-no-score-of-member
- **G3** tos-honest-no-detection-evasion
- **G4** murakumo-only
- **G5** destructive-member-sig-dry-run
- **G6** outward-gated
- **G7** ingest-consent-gated
- **G8** cost-of-severance-honesty
- **G9** kotoba-eavt-audit

## Honest gaps (R1)

- live execution is NOT wired — the driver returns authorization descriptors with
  `executed=false`; `plan/execute` raises (G6: Council Lv6+ + operator + member capability).
- catalog is `:representative` — operator-verified = 0; an operator must verify each
  procedure (and ToS stance) against its disclosed source before any live use.
- CBOR-CACAO byte-parity vs the live kotoba node is the operator step (cap bundle uses a
  canonical-JSON envelope in `tools/issue_capability.cljc`).
- clj/ langgraph lane: R1 functionally equivalent — `kaiyaku.cap` + `kaiyaku.driver` +
  `kaiyaku.catalog` clj-native, wired into agent.cljc as the :dispatch node (:approve →
  :dispatch → :rehearse, capability-gated authorization + catalog-enriched descriptor,
  executed=false; `clojure -X:test`). Both lanes carry R1; catalog EDN is shared.
