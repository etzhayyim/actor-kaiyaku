# kaiyaku 解約 — R1 execution-leg operator RUNBOOK

ADR-2606112201 R1. This is the operator how-to for the R1 severance leg. **R1 is
dry-run throughout**: the driver AUTHORIZES, a post-R1 component executes (G6).
There is NO live cancellation I/O in this codebase (`plan/execute` raises). For
the at-a-glance state see the generated [`MATURITY.md`](MATURITY.md).

## Pieces (methods/, bb-runnable)

| file | role |
|---|---|
| `cap.cljc` | the revocable CACAO severance capability (present-only; the member's `approved` svc-id allowlist = G5 in the leash) |
| `tools/issue_capability.cljc` | the MEMBER's own signing-runtime tool that mints the capability bundle (Ed25519/JDK; kaiyaku never signs) |
| `catalog.cljc` + `data/cancel-procedures.kotoba.edn` | real-service 解約 procedures (`:representative`, operator-verified=false) |
| `driver.cljc` | authorize-never-execute dispatch (cascade + exactly-once; `executed=false` always) |
| `karakuri_bridge.cljc` | maps an authorized plan → a karakuri `serviceOp` (validated vs karakuri's lexicon) |
| `receipt.cljc` | persists catalog + authorization receipts to the kotoba commit-DAG (G9) |
| `audit.cljc` | reads the receipt log back + the standing `no-live-execution?` verification (G6) |
| `pipeline.cljc` | composes all of the above end-to-end (dry-run) |

## Run the demo (no capability → all refused, honestly)

```bash
# from repo root
bb 20-actors/kaiyaku/methods/pipeline.cljc      # → out/pipeline-member-report.md + out/pipeline-summary.edn
bb 20-actors/kaiyaku/methods/maturity.cljc      # → MATURITY.md (regenerate after manifest/catalog edits)
bash 20-actors/kaiyaku/run_tests.sh             # full bb suite
```

## Issue a capability (MEMBER's own machine)

The member runs `tools/issue_capability.cljc` in their OWN runtime with their own
Ed25519 key, signing over EXACTLY the `approved` svc-ids they consent to sever (the
G5 human-in-the-loop set). kaiyaku holds no key and only PRESENTS the resulting
bundle. **Honest scope:** the bundle's `cacao_b64` is a canonical-JSON `{p,s}`
envelope; producing the exact CBOR-CACAO the live kotoba node verifies — and
confirming byte-parity against the running node — is an operator step (mirrors the
catalog's operator-verified=false).

## Self-check (operator/CI invariant)

```clojure
;; run-seed → persist receipts → read back → assert no live execution
(kaiyaku.methods.pipeline/operator-self-check! actor-dir "/tmp/kaiyaku-receipts.edn")
;; => {:severable N :authorized 0 :refused N :receipts N :audit-clean? true :all-executed-false? true}
```

`:audit-clean?` is a standing **G6 verification** over the persisted log (no receipt
ever records a live execution: `executed=0 ∧ server-signed=0`).

## The path to live (G6 — NOT in this codebase)

A live cancellation requires ALL of: a member-presented, unexpired capability whose
`approved` list contains the svc (G5/cap), the driver authorization (cascade +
exactly-once), **Council Lv6+ + operator attestation** (G6), and a verified
CBOR-CACAO bundle against the live kotoba node. Only then does a post-R1 driver —
distinct from `driver.cljc`, which authorizes but never executes — perform the T1
official-API call / T2 ToS-permitted browser-use / T3 self-submit handoff. T3 is
ALWAYS the member's own manual procedure; kaiyaku never submits it.
