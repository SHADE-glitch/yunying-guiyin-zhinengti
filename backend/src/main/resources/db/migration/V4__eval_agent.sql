-- Agent 任务级评测用例。
--
-- 只存"题目和期望"，不存运行结果：结果每次跑都不一样，落库反而会让人分不清
-- 哪份是当前代码跑出来的。历史结果要看 agent_run（每次评测都会留下 trace）。
create table eval_agent_case (
    id               bigserial primary key,
    question         text        not null,
    kind             varchar(16) not null,
    expected_outcome varchar(32),
    expected_leaf    varchar(64),
    expected_path    jsonb,
    note             text,
    constraint ck_eval_case_kind check (kind in ('ATTRIBUTION', 'LOOKUP')),
    constraint ck_eval_case_outcome check (expected_outcome is null or expected_outcome in (
        'DOMINANT_FOUND', 'MULTI_FACTOR', 'NO_SIGNIFICANT_CHANGE', 'INSUFFICIENT_EVIDENCE',
        'DATA_INCOMPLETE', 'INVALID_PROPOSAL_LIMIT', 'BUDGET_EXHAUSTED', 'FAILED')),
    constraint ck_eval_case_lookup_shape check (kind = 'ATTRIBUTION' or expected_outcome is null)
);
