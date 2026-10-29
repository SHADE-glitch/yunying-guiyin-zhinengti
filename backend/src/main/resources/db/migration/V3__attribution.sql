-- V3：归因模式的持久化。
--
-- 复用 agent_run / agent_step，不重复存 SQL。节点只存 evidence_step_id 指针 + 指纹，
-- 需要原文时 join 回 agent_step。两份原文只会带来"哪份是真的"的问题。
--
-- 历史数据兼容：存量 agent_run 全部是查数，mode 用默认值 'LOOKUP' 语义正确。
-- PG 11+ 加带默认值的列不重写表，存量行自动填充，无需回填脚本。

alter table agent_run add column mode varchar(16) not null default 'LOOKUP';
alter table agent_run add column attribution_outcome varchar(32);

alter table agent_run add constraint ck_run_mode
    check (mode in ('LOOKUP', 'ATTRIBUTION'));

-- 结局枚举与 AttributionOutcome 一一对应。查数流程不写，存量为 null。
alter table agent_run add constraint ck_run_attribution_outcome
    check (attribution_outcome is null or attribution_outcome in (
        'DOMINANT_FOUND', 'MULTI_FACTOR', 'NO_SIGNIFICANT_CHANGE', 'DATA_INCOMPLETE',
        'INSUFFICIENT_EVIDENCE', 'INVALID_PROPOSAL_LIMIT', 'BUDGET_EXHAUSTED', 'FAILED'));

create table attribution_node (
    id               bigserial primary key,
    run_id           uuid        not null references agent_run (id) on delete cascade,
    parent_id        bigint      references attribution_node (id) on delete cascade,
    depth            smallint    not null,
    dimension        varchar(32),
    target_value     varchar(64),
    scope            jsonb       not null default '{}'::jsonb,
    metric_code      varchar(64) not null,
    window_start     timestamptz not null,
    window_end       timestamptz not null,
    baseline_start   timestamptz not null,
    baseline_end     timestamptz not null,
    current_value    numeric(18,2),
    baseline_value   numeric(18,2),
    delta            numeric(18,2),
    contribution     numeric(10,4),
    verdict          varchar(16) not null,
    -- 证据必须指向真实步骤。外键保证"存在"，EvidenceBinding 再保证"指纹一致"。
    -- 步骤被删时置空而不是级联删节点：结论本身还有价值，只是失去了原始查询。
    evidence_step_id bigint      references agent_step (id) on delete set null,
    query_hash       char(64),
    row_count        int,
    created_at       timestamptz not null default now(),

    constraint ck_node_depth check (depth >= 0),
    constraint ck_node_verdict check (verdict in ('ROOT', 'PROBED', 'DOMINANT', 'FAILED')),
    constraint ck_node_dimension check (dimension is null or dimension in ('REGION', 'CATEGORY', 'SHOP')),
    -- depth 0 是根节点：没有维度和取值；其余层必须有。
    constraint ck_node_root_shape check ((depth = 0) = (dimension is null)),
    -- 同一个父节点下，同一维度的同一个取值只能有一行。
    -- 用 nulls not distinct：depth 1 的 parent 是根节点，其 parent_id 只有根那一行是 null，
    -- 默认的"NULL 互不相等"会让这条约束在根节点上失效。
    constraint uk_node_parent_dim_value
        unique nulls not distinct (run_id, parent_id, dimension, target_value)
);

create index idx_attr_run on attribution_node (run_id, depth, id);
