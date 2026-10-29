-- V7：最小会话。
--
-- 只做一件事：让追问补全有据可依。结构化状态存指标 / 根范围 / 最近一次 runId；
-- 原文只留最近的轮次（查询时 limit 3），不做长期记忆、不做摘要压缩。
-- 状态里的范围是"上一轮解析出的根"，不是结论——业务数据永远重查，旧结论不回灌。

create table session_state (
    id          uuid primary key,
    metric      varchar(32) not null default 'gmv',
    scope       jsonb       not null default '{}'::jsonb,
    last_run_id uuid,
    turn_count  int         not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table session_turn (
    id         bigserial primary key,
    session_id uuid        not null references session_state (id) on delete cascade,
    turn_no    int         not null,
    question   text        not null,
    resolved   text,
    mode       varchar(16) not null default 'ATTRIBUTION',
    run_id     uuid,
    created_at timestamptz not null default now()
);

create index idx_session_turn on session_turn (session_id, turn_no desc);

-- 会话表是应用自己的账本，运行时只读账号需要写权限（与 V6 的 trace 三表同一类）
grant insert, update on session_state, session_turn to insight_ro;
grant usage, select on sequence session_turn_id_seq to insight_ro;
