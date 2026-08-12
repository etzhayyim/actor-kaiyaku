# kaiyaku 解約 — 縁切り (tie-severance) executor

Canonical repository: `etzhayyim/actor-kaiyaku`. Kaiyaku is the governed
service-tie severance organ of the Tamaki-centered Etzhayyim artificial
organism. It prepares member-approved, own-account-only dry-run plans; Tamaki
retains organism authority and no live cancellation bypasses member signature
or Council gates. The former `com-etzhayyim-kaiyaku` name remains a
compatibility redirect.

Canonical actor data is EDN (`manifest.edn`, `kotoba.app.edn`, `data/`). Runtime and tests are
Clojure/CLJC under `src/` and `test/`; JSON interoperability belongs only in `wire/`.
Go/TinyGo, Python, and shell implementations are deprecated and pruned, not ported.

Run `clojure -M -m kaiyaku.test-runner` and `bb scripts/audit.clj`.

The default suite is actor-local. Tate handoff and Karakuri lexicon integration suites require
their repositories' canonical EDN fixtures to be mounted by the west workspace integration job.

**member 自身のサービスとの縁 (サブスク・休眠アカウント・カード継続課金・SSO/支払依存)
を edge-primary に台帳化し, 不要な縁を安全に切るための executor。**
Identifies the member's own unused subscriptions, dormant accounts and
unrecognized recurring charges, and turns approved severances into dry-run
plans through the safest adapter tier (T1 official API > T2 ToS-permitted
browser > T3 self-submit). Human relationships are structurally out of scope.

- 設計: [`CLAUDE.md`](CLAUDE.md) · ADR-2606112201
- tate 盾 との compose: 不利条項検出 → `kaiyaku-handoff.edn` → notice-window
  ワークリスト ([`methods/handoff_ingest.py`](methods/handoff_ingest.py))
- Hard lines: 縁の対象は常にサービス (人間関係は kokoro 心へ) · detection-evasion
  表現不能 · 解約実行は member-sig + dry-run + Council ゲート (`execute()` raises) ·
  違約金・予告期間は開示し回避しない

License: Apache 2.0 + etzhayyim Charter Compliance Rider (see repo root).
