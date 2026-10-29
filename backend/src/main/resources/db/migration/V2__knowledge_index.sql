-- 知识索引：把"指标口径 / 平台规则 / 常见问题"统一成可检索的文档。
--
-- 为什么不直接用 tsvector 做关键词召回：Postgres 自带的分词器不支持中文，
-- to_tsvector('simple', '退款率怎么算') 会把整串当成一个 token，召回几乎为 0。
-- 装 zhparser 需要自定义镜像和词典维护，成本不匹配。所以关键词路用 pg_trgm 三元组。
-- 这是一个明确的取舍，不是退而求其次——三元组对中文短查询的效果够用，且零运维。

create extension if not exists vector;
create extension if not exists pg_trgm;

create table knowledge_doc (
    id             bigserial primary key,
    doc_type       varchar(16)  not null,   -- METRIC / RULE / FAQ
    code           varchar(64)  unique,
    title          varchar(128) not null,
    content        text         not null,
    synonyms       text[]       not null default '{}',
    sql_template   text,
    related_tables text[]       not null default '{}',
    -- 向量：1024 维，和 text-embedding-v4 对齐
    embedding      vector(1024)
);

create index idx_kd_embedding on knowledge_doc using hnsw (embedding vector_cosine_ops);
create index idx_kd_title_trgm on knowledge_doc using gin (title gin_trgm_ops);
create index idx_kd_content_trgm on knowledge_doc using gin (content gin_trgm_ops);

-- 评测集：query + 人工标注的相关文档 id。召回率全靠这张表，没有它所有效果数字都不可报。
create table eval_case (
    id               bigserial primary key,
    query            text   not null,
    expected_doc_ids bigint[] not null,
    note             text
);

-- metric_definition 的职责被 knowledge_doc 取代：口径和规则本来就是同一类知识，
-- 分成两张表会让检索要么漏召回、要么写 union。
drop table if exists metric_definition;
