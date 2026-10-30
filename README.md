<p align="right"><a href="README.md"><b>English</b></a> | <a href="README.zh-CN.md">简体中文</a></p>

# 🧭 运营归因智能体 (Operational Attribution Agent)

An **operational attribution agent** for local-life business data. You ask *"why did East-China GMV
drop last week?"* and it answers with a drill-down path you can trace and a three-part conclusion —
**facts / inferences / uncertainty** — where every fact stays bound to the query that produced it.

The governing rule is **the model proposes, the code decides**. The model may propose exactly one
drill-down dimension per step, through a single-enum tool call. Contribution thresholds, depth and
probe budgets, and every SQL guardrail are deterministic code, not prompt instructions.

> **Public, code-only showcase.** This repository carries the runnable code and the stack. Full
> development history, evaluation batches and interview notes live in a private archive.

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring_AI-6DB33F?logo=spring&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![pgvector](https://img.shields.io/badge/pgvector-HNSW-336791?logo=postgresql&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3.5-42b883?logo=vue.js&logoColor=white)
![Docker Compose](https://img.shields.io/badge/Docker%20Compose-ready-2496ED?logo=docker&logoColor=white)

[![Backend CI](https://github.com/SHADE-glitch/yunying-guiyin-zhinengti/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/SHADE-glitch/yunying-guiyin-zhinengti/actions/workflows/backend-ci.yml)

**What it is** — a Spring Boot 3.5 service plus a Vue 3 console over PostgreSQL 16 (pgvector +
pg_trgm), with a hand-written agent tool loop, SSE streaming and a read-only database role.

---

## 🎯 The problem

A dashboard tells you *that* GMV fell. It does not tell you *where* the fall came from, and a model
asked to explain it will happily invent a plausible cause. This project answers the second question
while refusing to answer beyond its evidence:

- **Bounded exploration.** The agent drills from the top metric into dimensions until a contribution
  threshold, a depth limit or a probe budget says stop — all three gates are code.
- **Traceable conclusions.** Each attribution node is foreign-keyed to the evidence step and the SQL
  fingerprint behind it, so a claim can be clicked back to the query that supports it.
- **Honest uncertainty.** A fact is only printed when its numbers appear in the evidence text;
  otherwise the answer degrades to "no supporting evidence" instead of guessing.

## 🔁 The loop

```
baseline → probe* → verdict → answer → done
```

Streamed over SSE. Every step is persisted in three tables — `agent_run`, `agent_step`,
`attribution_node` — carrying per-step latency, token usage and the SQL fingerprint. A client
disconnect stops the loop before it touches the database again.

## 🌟 Key engineering highlights

### 1. A hand-written, bounded agent loop
No framework auto-execution. The tool loop is explicit: one `propose_dimension` call per step, a
single-enum schema, and a state machine whose termination gates are evaluated in code. Invalid
proposals are counted and eventually terminate the run rather than retrying forever.

### 2. An evidence chain that can refuse to answer
The conclusion is three-part (facts / inferences / uncertainty). Facts are checked against the
evidence text; a mismatch downgrades the section instead of shipping the number. When the data is
incomplete the model is not called at all.

### 3. Hybrid retrieval without a Chinese tokenizer
pgvector HNSW for the vector path, pg_trgm trigrams for the keyword path (no word segmenter is
available for Chinese), fused with RRF. Retrieval quality is measured on a 40-query hand-labelled
set — recall@5 and MRR are both 0.9875 — and the three retrieval modes can be re-run side by side.

### 4. Three parallel guardrail layers
An entry scope guard (three-valued enum: in scope / out of scope / needs clarification, evaluated
before the loop starts), four-layer static SQL validation, and a read-only database role that
physically cannot write. Write statements, multi-statement input and comment injection are rejected
at the tool layer.

### 5. Budget, degradation and cost
The query budget is deducted **before** execution, so a run cannot overspend; a token ceiling trips a
circuit breaker. When the model fails, the run degrades to a rule-based proposer and the report
degrades to computed results — and the event stream says so instead of pretending otherwise.

## 🧰 Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 17 · Spring Boot 3.5 · Spring AI (OpenAI-compatible endpoint) |
| Data | PostgreSQL 16 · pgvector (HNSW) · pg_trgm · Flyway |
| Agent | Hand-written tool loop · single-enum `propose_dimension` tool call · bounded state machine |
| Streaming | SSE (`baseline → probe* → verdict → answer → done`) |
| Frontend | Vue 3 · Vite · TypeScript · Element Plus · Pinia |
| Testing | JUnit 5 · Testcontainers (a real PostgreSQL per test run) |
| Runtime | Docker Compose |

## 🚀 Quick start

Prerequisites: JDK 17, Maven, Node 20+, Docker.

```bash
# 1. PostgreSQL 16 + pgvector on :5433
docker compose up -d

# 2. Configuration
cp .env.example .env          # fill LLM_API_KEY for the real model

# 3. Backend on :8080 — Flyway migrates on start
cd backend && mvn spring-boot:run

# 4. Console on :5173
cd frontend && npm install && npm run dev
```

Demo data lives in `backend/scripts/seed.sql` and `backend/scripts/seed_knowledge.sql`; load them
into the `insight` database after the first start.

### Offline demo, no API key

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

The `mock` profile disables the model proposer, so the run uses the rule-based proposer and still
walks the full drill-down path.

### Tests

```bash
cd backend && mvn test
```

## 🗂️ Layout

```
backend/     Spring Boot service — agent loop, retrieval, guardrails, SSE
frontend/    Vue 3 console — ask, watch the drill-down, read the report
docker-compose.yml   PostgreSQL 16 + pgvector
```

## 📊 Measured (local, single machine)

| Figure | Value |
|---|---|
| Backend tests | 32 classes / 211 passing |
| Retrieval recall@5 / MRR | 0.9875 / 0.9875 on 40 hand-labelled queries |
| Agent eval | 42 / 42 main cases |
| Holdout | 13 / 13 after the session-scope fix (10 / 13 on the first pass) |
| Per-run cost | 2.4 steps / ~3.9k tokens on average (post-fix batch; 2.7 steps on the baseline batch) |
| Ablation | depth=1 36 / 42, rejected by the gate; token-saving variants not made default |

These are local results on one machine (2026-10-29 batch, local seed data), not a production benchmark.
The holdout set is separate from the 42 tuning cases and was not used to tune prompts.

## 📄 License

No license file is included yet. Until one is added, all rights are reserved by the author.
