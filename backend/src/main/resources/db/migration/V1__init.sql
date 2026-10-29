-- 业务表：本地生活运营域
-- 设计取舍：订单表冗余 region_id / category_id，避免每次归因都要三表 join。
-- 代价是写入时多两次查表；分析场景读远多于写，值得。

create table region (
    id        bigserial primary key,
    name      varchar(64) not null,
    level     smallint    not null,          -- 1=大区 2=城市
    parent_id bigint references region (id)
);

create table category (
    id        bigserial primary key,
    name      varchar(64) not null,
    parent_id bigint references category (id)
);

create table shop (
    id          bigserial primary key,
    name        varchar(128) not null,
    category_id bigint       not null references category (id),
    region_id   bigint       not null references region (id),
    created_at  timestamptz  not null default now()
);

create table app_user (
    id            bigserial primary key,
    nickname      varchar(64),
    region_id     bigint references region (id),
    registered_at timestamptz not null
);

create table voucher (
    id          bigserial primary key,
    shop_id     bigint        not null references shop (id),
    title       varchar(128)  not null,
    face_value  numeric(10,2) not null,
    threshold   numeric(10,2) not null default 0,
    total_stock int           not null,
    valid_from  date          not null,
    valid_to    date          not null
);

create table voucher_order (
    id           bigserial primary key,
    user_id      bigint        not null references app_user (id),
    voucher_id   bigint        not null references voucher (id),
    shop_id      bigint        not null references shop (id),
    region_id    bigint        not null references region (id),
    category_id  bigint        not null references category (id),
    pay_amount   numeric(10,2) not null,
    status       varchar(16)   not null,   -- PAID / USED / REFUNDED
    created_at   timestamptz   not null,
    paid_at      timestamptz,
    used_at      timestamptz,
    refunded_at  timestamptz
);

create index idx_order_created  on voucher_order (created_at);
create index idx_order_region   on voucher_order (region_id, created_at);
create index idx_order_category on voucher_order (category_id, created_at);
create index idx_order_shop     on voucher_order (shop_id, created_at);

-- 指标口径：Agent 的 RAG 语料。模型不知道"GMV"在本平台怎么算，必须查这张表。
create table metric_definition (
    id             bigserial primary key,
    code           varchar(64) not null unique,
    name           varchar(64) not null,
    synonyms       text[]      not null default '{}',
    definition     text        not null,
    sql_template   text        not null,
    related_tables text[]      not null default '{}'
);

-- 运行记录：可观测与审计的基础。每次提问一行，每一步一行。
create table agent_run (
    id                uuid primary key,
    question          text        not null,
    status            varchar(16) not null,      -- RUNNING / SUCCEEDED / FAILED
    step_count        int         not null default 0,
    prompt_tokens     int         not null default 0,
    completion_tokens int         not null default 0,
    answer            text,
    error             text,
    created_at        timestamptz not null default now(),
    finished_at       timestamptz
);

create table agent_step (
    id                bigserial primary key,
    run_id            uuid        not null references agent_run (id) on delete cascade,
    step_no           int         not null,
    type              varchar(24) not null,      -- TOOL_CALL / TOOL_RESULT / ANSWER / ERROR
    tool_name         varchar(64),
    input             jsonb,
    output            jsonb,
    latency_ms        int,
    prompt_tokens     int         not null default 0,
    completion_tokens int         not null default 0,
    created_at        timestamptz not null default now()
);

create index idx_step_run on agent_step (run_id, step_no);
