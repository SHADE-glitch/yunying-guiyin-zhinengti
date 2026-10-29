-- V8：可评测、可对比、可回归的评测闭环。
--
-- 一次评测是一个批次：记录代码版本、模型、as-of、配置、prompt 指纹和数据集版本，
-- 结果逐用例落库。没有这些元数据，"这次比上次好"就只是一句话。
--
-- 数据集扩展：除了 ATTRIBUTION / LOOKUP，增加 GUARD（入口/指标/范围守卫）
-- 和 SESSION（追问补全）。查数答案允许多个可接受关键词，避免语义正确
-- 但字面不同的答案被误杀。

create table eval_batch (
    id              uuid primary key,
    kind            varchar(16) not null,
    label           varchar(128),
    note            text,
    status          varchar(16) not null default 'RUNNING',
    baseline_id     uuid references eval_batch (id) on delete set null,
    git_sha         varchar(64),
    model           varchar(128),
    embedding_model varchar(128),
    as_of           timestamptz,
    config          jsonb not null default '{}'::jsonb,
    prompt_hashes   jsonb not null default '{}'::jsonb,
    dataset_version varchar(64),
    dataset_size    int not null default 0,
    progress_done   int not null default 0,
    progress_total  int not null default 0,
    summary         jsonb,
    error           text,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,

    constraint ck_eval_batch_kind check (kind in ('AGENT', 'RETRIEVAL')),
    constraint ck_eval_batch_status check (status in ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

create index idx_eval_batch_kind_started on eval_batch (kind, started_at desc);

create table eval_case_result (
    id              bigserial primary key,
    batch_id        uuid not null references eval_batch (id) on delete cascade,
    case_id         bigint,
    question        text not null,
    kind            varchar(16) not null,
    expected        text,
    actual          text,
    passed          boolean not null,
    outcome_match   boolean not null default true,
    path_match      boolean not null default true,
    leaf_match      boolean not null default true,
    answer_match    boolean not null default true,
    steps           int not null default 0,
    probes          int not null default 0,
    tokens          int not null default 0,
    degraded        boolean not null default false,
    failure_kind    varchar(32),
    run_id          uuid,
    duration_ms     int,
    detail          jsonb
);

create index idx_eval_case_batch on eval_case_result (batch_id, case_id);

create table eval_retrieval_result (
    id              bigserial primary key,
    batch_id        uuid not null references eval_batch (id) on delete cascade,
    mode            varchar(16) not null,
    query           text not null,
    expected_ids    bigint[] not null,
    retrieved_ids   bigint[] not null,
    recall          numeric(6,4) not null,
    reciprocal_rank numeric(6,4) not null
);

create index idx_eval_retrieval_batch on eval_retrieval_result (batch_id, mode);

-- 运行时账号写评测批次与结果；业务数据仍然只读。
grant insert, update on eval_batch, eval_case_result, eval_retrieval_result to insight_ro;
grant usage, select on sequence eval_case_result_id_seq, eval_retrieval_result_id_seq to insight_ro;
-- 会使用例由评测自己建、自己删；session_state 是应用账本，不是业务数据。
grant delete on session_state to insight_ro;

-- ---- 数据集扩展 ----
alter table eval_agent_case add column expected_error varchar(32);
alter table eval_agent_case add column setup_question text;
alter table eval_agent_case add column expected_resolved_contains text[];
alter table eval_agent_case add column expected_answer_any text[];

alter table eval_agent_case drop constraint ck_eval_case_kind;
alter table eval_agent_case add constraint ck_eval_case_kind
    check (kind in ('ATTRIBUTION', 'LOOKUP', 'GUARD', 'SESSION'));

-- GUARD / SESSION 不要求 outcome；旧约束只覆盖 LOOKUP，这里显式写全。
alter table eval_agent_case drop constraint ck_eval_case_lookup_shape;
alter table eval_agent_case add constraint ck_eval_case_shape
    check (kind = 'ATTRIBUTION' or expected_outcome is null);
