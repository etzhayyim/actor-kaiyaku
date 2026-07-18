# kaiyaku 解約 — 縁切り (tie-severance) executor

**DID**: `did:web:etzhayyim.com:actor:kaiyaku` (aka legacy `did:web:kaiyaku.etzhayyim.com`) ·
**Tier**: B · **Status**: 🟡 R0 · **ADR**: 2606112201 · **depends**: 2606039200 (karakuri
ServiceOp tiers) · 2606072400 (organizer upstream) · 2605231525 (no-server-key) ·
2605215000 (Murakumo-only) · 2605312345 (Datom = canonical state)

## What this is

The actor that **severs the member's own unwanted service ties** — the missing executor
the organizer subscription-discovery pipeline has pointed at since its design
(`mailer → organizer → kaiyaku`, organizer CLAUDE.md). 縁切り here is the member releasing
their OWN accumulated digital ties: **unused subscriptions (解約), dormant accounts (退会),
unrecognized recurring card charges, and the SSO / payment-method dependencies** that make
severing them risky.

Three legs:

1. **縁-ledger** — the member's service ties as `:en/*` edges over `:svc/*` nodes in the
   kotoba Datom log (R0 = synthetic demo seed; live ingest from mailer/organizer/card-export
   is G7-gated).
2. **enkiri analyze** (`methods/analyze.py`) — per-TIE burden (cost × unused fraction +
   dormancy) routed to `:keep / :review / :sever`, using the **disclosed organizer
   thresholds** (usage<20 ∧ cost>¥500 → sever; <50 → review; cost-free account dormant
   ≥365d → sever) + a **dependency cascade-guard**: a severable service that other ties
   stand on (SSO / payment-method) downgrades to `:review-cascade` and plans a
   `rehome-dependency` step first — 依存 is detected, never blindly cut.
3. **severance plan** (`methods/plan.py`) — an approved `:sever` becomes a dry-run plan
   through the safest adapter tier (karakuri pattern): **T1 official-API cancel > T2
   ToS-permitted browser-use > T3 self-submit 解約/退会 procedure** (toritsugi/kurashimori
   default-self-submit). Every plan exports the member's own data before closure and ends
   with a closure confirmation step.

## Hard gates (constitutional — read before any change)

- **G1 member-principal, own ties only.** The ledger is the member's OWN service ties,
  consent-bound; live member facts ship encrypted (`com.etzhayyim.encrypted.*`). R0 seed
  is fully `:synthetic`. Never a third party's accounts.
- **G2 edge-primary, no score-of-member.** Burden / recommendation live ONLY on ties,
  computed on READ (emitted as `:bond/is-transient` datoms). There is no per-member
  score and **no "toxic person" rating** (反個人主義).
- **G3 ToS-honest, no detection-evasion.** T2 only where the service browser stance
  permits; `:prohibited` / `:unknown` refuses T2 **by construction** (`select_tier`);
  evasion verbs (captcha-solve / proxy-rotate / stealth / rate-limit-bypass /
  fingerprint-spoof) are **unrepresentable** — `_make_step` raises.
- **G5/G6 destructive-gated.** 解約/退会 is destructive: member-sig + explicit dry-run
  confirm required on every plan; **live execution is Council Lv6+ + operator gated** —
  `execute()` raises at R0.
- **G8 cost-of-severance honesty.** Notice period / 違約金 are carried into every readout
  and plan and **never planned around**; thresholds are the disclosed organizer rules.
- **G9 kotoba-EAVT audit.** Every readout + plan is a Datom; the member can audit
  exactly what kaiyaku touched.

## Non-goals

**N1 — NOT a human-relationship severance tool.** A tie target is always a SERVICE
(`:svc/*`), never a person (enforced by test). No contact-blocking, no relationship
scoring; a member dealing with a harmful human relationship routes to **kokoro 心**
(mental-health support, ADR-2605263700). · N2 no retention-flow trickery / anti-bot
circumvention · N3 no debt evasion / 取立 / chargeback abuse (kurashimori owns
クーリングオフ/返金 **rights**; kaiyaku owns 解約/退会 **execution**) · N4 no third-party
account operation / credential custody · N5 not a mass-unsubscribe bot · N6 no financial
advice.

## Boundaries (who owns what)

| Concern | Owner |
|---|---|
| Detect subscriptions from billing mail, monthly usage scoring | **organizer** (upstream; Follow on mailer inboundEmail) |
| クーリングオフ / 返金 / 消費者庁 escalation (rights) | **kurashimori** |
| Generic web-service ServiceOp adapters (T1/T2 engine, ToS stances) | **karakuri** (kaiyaku composes; never re-implements) |
| 解約 / 退会 decision-ledger + severance plan + (gated) execution | **kaiyaku** (this actor) |
| 不利条項の検出 + 法的手続きへの応答 (防御) | **tate 盾** (ADR-2606112301; its `:kaiyaku` routes feed this ledger via `kaiyaku-handoff.edn` → `handoff_ingest.py` — 自動更新窓の notice-days カレンダー化, wave 26 で往復配線) |
| カード明細からの定期課金検出 (recurring-charge) | **meisai 明細** (ADR-2606122400; its `recurring.cljc` handoff `data/kaiyaku-handoff.edn` → `meisai_ingest.cljc` becomes a `:recurring-charge` tie over a `:svc/kind :card-merchant` node — kaiyaku decides keep/review/sever, meisai never does) |
| Harmful human relationships | **kokoro** (support; kaiyaku N1 refuses the domain) |

## Layout

```
20-actors/kaiyaku/
├── CLAUDE.md                          # this file
├── manifest.edn                       # actor manifest (5 cells, 9 gates, 6 non-goals)
├── data/
│   ├── seed-en-ledger.kotoba.edn      # SYNTHETIC demo 縁-ledger (no real PII — G1)
│   └── cancel-procedures.kotoba.edn   # R1: REAL-service 解約 procedure catalog (:representative, operator-verified=false)
├── methods/                           # pure-stdlib → kotoba pywasm-runnable
│   ├── analyze.py                     # edge-primary tie-burden analyzer + cascade-guard
│   ├── plan.py                        # T1/T2/T3 severance-plan builder (dry-run only)
│   ├── cap.cljc                       # R1: severance CAPABILITY (revocable leash; present-only, no-server-key)
│   ├── driver.cljc                    # R1: capability-gated dispatch (authorize-never-execute; cascade + exactly-once)
│   ├── catalog.cljc                   # R1: real-service 解約 procedure catalog loader/validator (tier-parity w/ planner)
│   ├── receipt.cljc                   # R1: catalog + authorization-receipt Datom emit/persist (G9 audit, no-secrets)
│   ├── karakuri_bridge.cljc           # R1: kaiyaku plan → karakuri serviceOp handoff (lexicon-checked, no-drift)
│   ├── pipeline.cljc                  # R1: end-to-end composition (analyze→plan→enrich→dispatch→serviceop→receipt)
│   ├── maturity.cljc                  # R1: generated MATURITY.md scorecard (manifest+catalog SoT, freshness-tested)
│   ├── audit.cljc                     # R1: G9 audit READ side — query receipt log + standing no-live-execution check
│   ├── handoff_ingest.py              # tate 盾 handoff → notice-window worklist (compose 往復)
│   ├── meisai_ingest.cljc             # meisai 明細 recurring-charge handoff → 縁-ledger tie (compose 往復)
│   └── datom_emit.py                  # kotoba Datom-log (EAVT) emitter — canonical state
├── tools/                             # MEMBER-side runtime (NOT the actor — may do crypto)
│   └── issue_capability.cljc          # R1: member mints the revocable severance capability (Ed25519/JDK; kaiyaku never signs)
├── MATURITY.md                        # GENERATED R1 scorecard (methods/maturity.cljc; freshness-tested)
├── R1-RUNBOOK.md                      # operator how-to for the R1 leg (issue capability → run → persist → audit → G6 path)
├── tests/                             # 130 tests, pure stdlib
│   ├── test_analyze.py
│   ├── test_handoff.py
│   ├── test_meisai_ingest.cljc        # meisai 明細 recurring-charge handoff → 縁-ledger tie
│   ├── test_plan.py
│   ├── test_cap.cljc                  # R1 capability: load/validation gate (malformed-bundle rejection)
│   ├── test_driver.cljc               # R1 driver: capability gating + cascade + exactly-once
│   ├── test_catalog.cljc              # R1 catalog: tier-parity w/ planner + G3/G6/G8 honesty
│   ├── test_issue_capability.cljc     # R1 tool: issuance↔cap verification roundtrip + Ed25519
│   ├── test_receipt.cljc              # R1 audit: catalog/receipt datoms + no-secrets + commit-DAG
│   ├── test_karakuri_bridge.cljc      # R1 seam: plan→serviceOp lexicon parity (no-drift)
│   ├── test_pipeline.cljc             # R1 END-TO-END: analyze→…→serviceop→receipt compose
│   ├── test_manifest.cljc             # R1 manifest↔methods↔karakuri-lexicon parity (no doc drift)
│   ├── test_maturity.cljc             # R1 MATURITY.md scorecard content + freshness
│   ├── test_audit.cljc                # R1 G9 audit read-back + standing no-live-execution check
│   └── test_charter_invariants.cljc   # CONSOLIDATED constitutional guarantees (G3/G5/G6/G8/N1) in one place
├── clj/                               # cljc port + Clojure LangGraph actor (see clj/README.md)
│   ├── deps.edn                       # langgraph-clj + browser-use-clj + computer-use-clj (git deps)
│   ├── src/kaiyaku/                   # ledger/analyze/plan/datoms (Python numeric parity)
│   │                                  #   + executor.cljc (T2 rehearsal engines; murakumo-model G4)
│   │                                  #   + agent.cljc (StateGraph: ingest→analyze→plan→‖member-sig‖→dispatch→rehearse)
│   │                                  #   + cap.cljc / driver.cljc / catalog.cljc (R1 clj-native; WIRED into agent :dispatch node, catalog-enriched)
│   └── test/kaiyaku/                  # 32 tests / 189 assertions (driver_test + catalog_test + agent :dispatch authorization)
└── out/                               # GENERATED — do not hand-edit
    ├── enkiri-readout.md
    ├── severance-plans.md
    └── enkiri-datoms.kotoba.edn
```

Two deployment lanes, one ADR, same gates: `methods/` = kotoba **pywasm**
(componentize-py) lane; `clj/` = **langgraph-clj / kotoba-clj** (portable
`.cljc`, Clojure-on-WASM) lane. The clj actor adds the graph-form member-sig
gate (langgraph interrupt before `:approve`) and T2 dry-run **rehearsal**
through browser-use-clj / computer-use-clj over injected mock surfaces —
live legs stay G6-gated in both lanes.

## Run

```bash
cd 20-actors/kaiyaku
python3 methods/analyze.py            # → out/enkiri-readout.md
python3 methods/plan.py               # → out/severance-plans.md (dry-run)
python3 methods/datom_emit.py         # → out/enkiri-datoms.kotoba.edn (EAVT)
python3 methods/handoff_ingest.py    # tate handoff → out/handoff-worklist.md
python3 tests/test_analyze.py && python3 tests/test_plan.py \
  && python3 tests/test_handoff.py   # 22 green

cd clj && clojure -X:test             # clj lane: 32 tests / 189 assertions green (R1 cap/driver/catalog wired into :dispatch)
```

## Do not

- Do not add a person / contact / relationship node kind to the ledger, or any
  per-member aggregate score — N1 / G2 (tests enforce both).
- Do not return T2 for a `:prohibited` or `:unknown` browser stance, and never add an
  evasion verb — G3 (`_make_step` raises; tests enforce).
- Do not let any code path execute a live cancellation — `execute()` raises at R0;
  live legs are Council Lv6+ + operator + member-sig gated (G5/G6).
- Do not plan around a notice period / 違約金, and do not absorb kurashimori's
  クーリングオフ/返金 scope — G8 / N3.
- Do not ingest real member data into `data/` — the committed seed stays `:synthetic`;
  live ingest is consent- + G7-gated and encrypted (ADR-2605181100).
