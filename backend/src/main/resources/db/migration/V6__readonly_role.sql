-- V6：运行时只读账号。
--
-- 业务表只给 SELECT；trace 三表额外给写——应用每次提问都要落痕，
-- 这三张表是应用自己的账本，不是业务数据。模型发出的 SQL 与 RunRecorder
-- 走同一个运行时连接，所以即使 SqlGuard 被绕过，能写到的最多是自己家的 trace，
-- 碰不到业务表（voucher_order 等）。
--
-- 密码是本地演示库的固定值（与既有 DB_PASSWORD=insight 同级），不是生产秘密；
-- 真实环境应由部署系统创建角色并轮换密码，迁移只负责授权结构。

create role insight_ro login password 'insight_ro';

grant connect on database insight to insight_ro;
grant usage on schema public to insight_ro;
grant select on all tables in schema public to insight_ro;
grant insert, update on agent_run, agent_step, attribution_node to insight_ro;
grant usage, select on all sequences in schema public to insight_ro;

-- 未来新表/新序列自动继承：业务表的默认权限是只读
alter default privileges in schema public grant select on tables to insight_ro;
alter default privileges in schema public grant usage, select on sequences to insight_ro;
