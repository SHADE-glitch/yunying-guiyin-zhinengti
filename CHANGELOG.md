# CHANGELOG — insight-agent

Application repository (Spring Boot + PostgreSQL/pgvector), **no upstream**. This record keeps the
repairs, the drift guards and the process changes — the things a rebuild, a dependency bump, or a
re-run of a quoted artifact can invalidate. Feature work is in `git log`, not here.

Entries are `D-###`, monotonic, never reused; a gap is a failure, not cleanup. An entry states what
was true **as of its commit**, not current state, and aggregate counts are printed by a command,
never copied into this file. `kind` ∈ `fix | perf | taste | guard | revert | chore`.

---

> **Note.** Paths under `docs/` named below are engineering notes kept outside this
> repository; each entry records them as they stood at that commit.

### D-001 · 2026-10-28 · chore
Symptom  The root-file layer was absent: no `AGENTS` pair, no `README` pair, no `MAINTENANCE`,
         no `CHANGELOG`, no `opencode.json`, no CI workflow, and `.gitignore` lacked the
         machine-wide runtime ignore list.
Change   Adopt the dimension-B layer: add the `AGENTS` / `README` / `MAINTENANCE` bilingual pairs,
         this `CHANGELOG`, `opencode.json` naming the two standard files, a `backend-ci` workflow
         running `mvn -B test`, and the runtime ignore list plus `reports/` to `.gitignore`.
Evidence Root files and ignore list only — no Java source, migration or test touched.
Cost     None to runtime behaviour. `README` is a placeholder by the owner's decision; the
         four-part opening is still owed.

### D-002 · 2026-10-28 · fix
Symptom  Two ways to get an answer that could not be trusted. A question naming another metric
         ("为什么退款率跌了") was answered with a GMV attribution — numbers correct, question
         different, and nothing in the response said so. And a question rejected before parsing
         (ambiguous scope, unsupported metric) left no `agent_run` row at all, so how often
         people ask unanswerable things was unmeasurable.
Change   `AttributionMetricGuard` rejects a non-GMV metric (word list taken from the knowledge
         base, not invented synonyms) unless a GMV word is also present, and the controller maps
         it to `UNSUPPORTED_METRIC`. `AttributionService.run()` now creates the run before
         parsing and writes `status=FAILED` / `attribution_outcome=FAILED` with the original
         message before rethrowing.
Evidence 160 tests green (was 146); `AttributionMetricGuardTest` (11), `AttributionControllerTest` (2),
         and two integration cases asserting the row exists. Live app: both error paths return their
         own code, `/api/attribute` success and `/api/ask` unchanged.
Cost     A question that names another metric is now refused instead of answered wrongly; a new
         metric still requires a `valueExpression` and a `knowledge_doc` row, plus a word here.

### D-003 · 2026-10-28 · chore
Symptom  The repository root held a single application, with no room for the planned frontend.
Change   `pom.xml`, `src/` and `scripts/` move to `backend/`. The root file layer,
         `docker-compose.yml` and `.env(.example)` stay at the root — they serve both apps.
         CI gets `working-directory: backend` and `cache-dependency-path`.
Evidence `cd backend && mvn -B test` → 160 green; the two integration tests resolve
         `scripts/seed.sql` from the module directory, unchanged.
Cost     Every command in the docs gains a `cd backend`. Relative links from `docs/` to source
         paths would break — there are none today.

### D-004 · 2026-10-29 · fix
Symptom  A question routed to `order_count` produced `count(*)` evidence, but every
         `attribution_node` row was stamped `metric_code='gmv'`. The evidence chain looked
         consistent while naming the wrong metric — "the SQL is right" did not mean "the
         evidence is labelled right".
Change   `AttributionNodeWriter` no longer reads `props.getMetric()`; the routed metric is passed
         from the run context through the listener into both root and split writes. The two
         integration entry points now assert the persisted `metric_code`.
Evidence 183 tests green (XML totals; was 184 before one parameterized case was removed). Live run:
         `为什么华东订单量跌了` → SQL uses `count(*)`, root 1,789 vs 2,080, hotpot 238 vs 504,
         persisted `metric_code=order_count`.
Cost     Writer signature changes; future metrics must thread the value through the same path,
         which is the point.

### D-005 · 2026-10-29 · fix
Symptom  Two linked failures. A city+category question leaves only SHOP to split; when one shop
         cleared the dominant threshold at depth 1, the engine still asked for another dimension,
         hit the invalid-proposal limit and reported `INVALID_PROPOSAL_LIMIT` instead of
         `DOMINANT_FOUND`. The old evaluation could not catch this: it ran synchronously, persisted
         no results, compared only outcome/path, and recorded no git/model/as-of/config/prompt/data
         version, so two runs were not comparable and guard/session behavior was never scored.
Change   `Dimension.leaf()` makes SHOP terminal in `AttributionEngine`; a dominant leaf stops with
         `DOMINANT_FOUND`. Evaluation becomes batch-based (V8): each batch freezes git sha, model,
         embedding model, as-of, config, prompt hashes and a content-only dataset hash; per-case
         results and retrieval recalls are persisted. The 42-case dataset adds guard/session cases,
         multi-keyword lookup checks and leaf-dimension coverage. Comparison reports metric deltas,
         case diffs and a gate over quality, safety, cost and comparability. Config and prompt
         variants live only inside a batch. `scripts/eval.py` starts, polls, exports and enforces
         the gate.
Evidence 207 tests green (was 183). Live batches: baseline `72182aed` 42/42, avg 2.69 steps /
         1.10 probes / 3,898 tokens, p50 6.5s / p90 17.2s; retrieval HYBRID recall@5 0.9875 /
         MRR 0.9875. `depth=1` (`c9008b41`) drops 6 East/South China cases from MULTI_FACTOR to
         DOMINANT_FOUND and fails the gate; rule proposer (`c94056b6`) and concise reporter
         (`c828d627`) pass but were not adopted (known-cases only; facts 147 → 62).
Cost     The engine's termination gate is locked decision logic; this is the one change to it,
         triggered by an evaluation case. Evaluation now spends real model quota and adds V8
         tables plus a two-worker async pool (queue capped at four); model aliases can drift, so
         batches record the exact model id (`deepseek-v4.1-flash-1017` here).

### D-006 · 2026-10-29 · docs
Symptom  The docs mixed current R9 metrics with W5-era numbers (183 tests, 30 cases, 0.988),
         the résumé map still described five highlights, and old notes/design/eval material sat
         in the live docs tree where readers could mistake it for current guidance.
Change   Rebuild `docs/90-evidence/00-metrics-and-evidence.md` around the 2026-10-29 batches
         (32 classes / 211 cases; 42/42 baseline and post-fix; holdout 10/13 → 13/13; retrieval
         0.9875/0.9875; ablations). Rewrite the résumé review and map for the 4+4 R9 structure.
         Add `docs/03-deepen/06-ai-code-review.md` with risk classes, eight-step acceptance and
         an explicit Unconfirmed boundary for AI involvement. Move old notes, design drafts, W5
         reports and the pre-R9 evidence snapshot under `docs/archive/2026-10/`, and point every
         live page at the archive instead of the removed paths.
Evidence Docs-only. 135 relative links resolve; the docs tree lists 35 of 35 Markdown files.
         No Java source, migration, test or evaluation artifact was changed. Historical numbers
         remain only in the archive or in sections labelled as historical.
Cost     Readers who land on old bookmarks must follow one archive hop. The evidence index must
         be refreshed again whenever a batch is rerun; copied numbers elsewhere are not current.

Commit   MIRROR-COMMIT (mirror of the source repository's change)

### D-007 · 2026-10-30 · docs
Symptom  The docs tree still carried pre-restructure names and drifted facts: a `99-worklog/`
         directory whose open items had no home, a `docs/eval/README.md` whose content had been
         absorbed into `90-evidence/02-evaluation.md`, no shared self-intro page under
         `04-interview/`, stale counts (AttributionEngineTest 15, FollowUpCompleterTest 7,
         Flyway V1–V7), hard-coded commit totals (58/72), concrete model ids in prose, and an
         evidence index that mixed full detail with indexing.
Change   Finish the six-layer restructure. Add `04-interview/00-self-intro.md` (byte-identical
         to the local-life copy). Add the 设计取舍 / 失败路径 rows to the four résumé cards and
         the bagu / interview / journey cross-reference table to the résumé map. Correct counts
         to AttributionEngineTest 16, FollowUpCompleterTest 11, Flyway V1–V8 (V8 eval tables
         added to `01-orientation.md` and `04-data-model.md`). Replace hard-coded commit totals
         with the `git log --oneline | wc -l` rule; drop concrete model ids from prose and defer
         to each batch JSON's `model` field. Add the read-only knowledge-base anchor table
         (file:// links) to `03-deepen/02-bagu-map.md`. Rewrite
         `90-evidence/00-metrics-and-evidence.md` as a pure index over 01/02/03. Migrate the
         worklog open items into `03-deepen/03-boundaries.md` §7, then delete `docs/99-worklog/`
         and `docs/eval/`. Rewrite `docs/README.md`'s tree and entry points to the final layout.
Evidence Docs-only; no Java source, migration, test or evaluation artifact touched. All 228
         relative links across 37 docs Markdown files resolve (14 file:// knowledge-base links
         counted, not resolved); the `docs/README.md` tree matches disk (48 entries). Local-life
         guards re-run green: check_doc_links (552 links, 35/35 files), check_layout,
         check_bilingual (3 pairs), check_backend_map.
Cost     `docs/eval/README.md` and the worklog are gone; their content lives in
         `02-evaluation.md` and `03-boundaries.md` §7 respectively. Root `README.md` remains a
         placeholder by the owner's decision.

Commit   MIRROR-COMMIT (mirror of the source repository's change)
