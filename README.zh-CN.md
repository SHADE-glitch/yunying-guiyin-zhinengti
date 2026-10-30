<p align="right"><a href="README.md">English</a> | <a href="README.zh-CN.md"><b>简体中文</b></a></p>

# 🧭 运营归因智能体

一个面向本地生活业务数据的**运营归因 Agent**。你问「上周华东 GMV 为什么跌了」，它给出可回溯的下钻
路径和三段式结论 —— **事实 / 推测 / 不确定** —— 每一条事实都绑定在产生它的那次查询上。

核心设计是**模型提议、代码裁决**：模型每一步只能通过单枚举 tool-call 提议一个下钻维度；贡献度阈值、
深度与探测预算、以及所有 SQL 护栏都是确定性代码，不是提示词里的叮嘱。

> **公开的纯代码展示仓。** 本仓只保留可运行代码与技术栈；完整开发历史、评测批次与面试文档在私有归档中。

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring_AI-6DB33F?logo=spring&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![pgvector](https://img.shields.io/badge/pgvector-HNSW-336791?logo=postgresql&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3.5-42b883?logo=vue.js&logoColor=white)
![Docker Compose](https://img.shields.io/badge/Docker%20Compose-ready-2496ED?logo=docker&logoColor=white)

[![Backend CI](https://github.com/SHADE-glitch/yunying-guiyin-zhinengti/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/SHADE-glitch/yunying-guiyin-zhinengti/actions/workflows/backend-ci.yml)

**这是什么** —— 一个 Spring Boot 3.5 服务加一个 Vue 3 控制台，数据侧是 PostgreSQL 16（pgvector +
pg_trgm），Agent 循环手写、结论走 SSE 流式推送、业务表由只读数据库账号访问。

---

## 🎯 要解决的问题

看板只告诉你 GMV **跌了**，不告诉你**跌在哪**；而直接让模型解释，它会给你一个听起来很合理的编造原因。
这个项目回答第二个问题，同时拒绝超出证据作答：

- **有界探索**：从顶层指标逐层下钻，直到贡献度阈值、深度上限或探测次数预算叫停 —— 三个门都是代码。
- **可回溯的结论**：每个归因节点外键绑定证据 step 与 SQL 指纹，结论可以点回支撑它的那次查询。
- **诚实的不确定**：事实里的数字必须在证据文本中命中才输出，否则该段降级为「无证据支撑」，而不是猜。

## 🔁 循环

```
baseline → probe* → verdict → answer → done
```

全程 SSE 流式。每一步落三张表 —— `agent_run`、`agent_step`、`attribution_node` —— 记录单步延迟、
token 用量与 SQL 指纹。客户端断开即停止，不再继续打库。

## 🌟 核心工程点

### 1. 手写的有界 Agent 循环
不依赖框架自动执行。工具循环是显式的：每步一次 `propose_dimension` 调用、单枚举 schema、终止门由
状态机在代码里判定。无效提议会被计数，累计到上限直接结束，而不是无限重试。

### 2. 一条可以拒绝作答的证据链
结论分三段（事实 / 推测 / 不确定）。事实段会拿证据文本核对数字，对不上就降级该段，而不是把数字发出去；
数据不完整时根本不调用模型。

### 3. 中文无分词器下的混合检索
向量路用 pgvector HNSW，关键词路用 pg_trgm 三元组（中文没有现成分词器），两路 RRF 融合。检索质量在
40 条人工标注集上度量 —— recall@5 与 MRR 均为 0.9875 —— 三种检索模式可以一键并排复跑。

### 4. 三层并列的护栏
入口越界守卫（三值枚举：在范围内 / 越界 / 需澄清，循环开始前判定）、SqlGuard 四层静态校验、以及物理上
写不进去的只读数据库账号。写语句、多语句、注释注入都在工具层被拒绝。

### 5. 预算、降级与成本
查询预算**先扣后执行**，所以一次运行不会超支；token 超限触发熔断。模型故障时降级到规则提议器、报告降级到
计算结果 —— 并且事件流里如实标注降级，而不是假装一切正常。

## 🧰 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.5 · Spring AI（OpenAI 兼容端点） |
| 数据 | PostgreSQL 16 · pgvector（HNSW）· pg_trgm · Flyway |
| Agent | 手写工具循环 · 单枚举 `propose_dimension` tool-call · 有界状态机 |
| 流式 | SSE（`baseline → probe* → verdict → answer → done`） |
| 前端 | Vue 3 · Vite · TypeScript · Element Plus · Pinia |
| 测试 | JUnit 5 · Testcontainers（每次测试起真 PostgreSQL） |
| 运行 | Docker Compose |

## 🚀 快速开始

环境要求：JDK 17、Maven、Node 20+、Docker。

```bash
# 1. PostgreSQL 16 + pgvector，映射到 :5433
docker compose up -d

# 2. 配置
cp .env.example .env          # 真模型需要填 LLM_API_KEY

# 3. 后端 :8080 —— 启动时 Flyway 自动迁移
cd backend && mvn spring-boot:run

# 4. 控制台 :5173
cd frontend && npm install && npm run dev
```

演示数据在 `backend/scripts/seed.sql` 与 `backend/scripts/seed_knowledge.sql`，首次启动后导入 `insight` 库。

### 离线演示（不需要 API Key）

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

`mock` profile 关掉模型提议器，改走规则提议器，仍然能跑完整的下钻链路。

### 测试

```bash
cd backend && mvn test
```

## 🗂️ 目录

```
backend/     Spring Boot 服务 —— Agent 循环、检索、护栏、SSE
frontend/    Vue 3 控制台 —— 提问、看下钻、读报告
docker-compose.yml   PostgreSQL 16 + pgvector
```

## 📊 实测（本地单机）

| 指标 | 值 |
|---|---|
| 后端测试 | 32 类 / 211 项通过 |
| 检索 recall@5 / MRR | 0.9875 / 0.9875（40 条人工标注） |
| Agent 评测 | 42 / 42 主集合 |
| Holdout | 会话范围继承修复后 13 / 13（首轮 10 / 13） |
| 单次运行成本 | 平均 2.4 步 / 约 3.9k token（修复后批次；基线批次 2.7 步） |
| 消融 | depth=1 仅 36 / 42，被门禁拦下；省 token 变体未设为默认 |

以上为 2026-10-29 批次、单机本地种子数据的结果，不是生产基准；holdout 与 42 条调优集分开，未参与提示词调优。

## 📄 许可证

暂未附许可证文件。在补充之前，作者保留所有权利。
