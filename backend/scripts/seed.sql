-- 确定性种子数据。setseed 保证每次生成的分布一致，便于复现归因结论。
select setseed(0.42);

truncate agent_step, agent_run, voucher_order, voucher, app_user, shop, category, region restart identity cascade;

-- 大区
insert into region (id, name, level, parent_id) values
    (1, '华东', 1, null), (2, '华南', 1, null), (3, '华北', 1, null), (4, '西南', 1, null);

-- 城市（每大区 4 城）
insert into region (id, name, level, parent_id)
select 100 + g,
       (array['上海','杭州','南京','苏州',
              '广州','深圳','东莞','佛山',
              '北京','天津','青岛','济南',
              '成都','重庆','昆明','贵阳'])[g],
       2,
       ((g - 1) / 4) + 1
from generate_series(1, 16) g;

-- 品类：一级 4 个，二级 10 个
insert into category (id, name, parent_id) values
    (1, '餐饮', null), (2, '休闲娱乐', null), (3, '丽人', null), (4, '亲子', null);
insert into category (id, name, parent_id) values
    (11, '火锅', 1), (12, '烧烤', 1), (13, '咖啡', 1),
    (21, 'KTV', 2), (22, '电影', 2), (23, '桌游', 2),
    (31, '美发', 3), (32, '美甲', 3),
    (41, '儿童乐园', 4), (42, '早教', 4);

-- 商户 200
insert into shop (id, name, category_id, region_id, created_at)
select g,
       '商户' || lpad(g::text, 3, '0'),
       -- 品类按真实平台分布加权：火锅是主力品类，占约 25%
       (array[11,11,11,11,12,12,13,13,21,22,22,23,31,32,41,42])[1 + floor(random() * 16)::int],
       101 + floor(random() * 16)::int,
       timestamptz '2025-06-01' + make_interval(days => (random() * 200)::int)
from generate_series(1, 200) g;

-- 用户 5000
insert into app_user (id, nickname, region_id, registered_at)
select g,
       'user' || lpad(g::text, 4, '0'),
       101 + floor(random() * 16)::int,
       timestamptz '2025-06-01' + make_interval(days => (random() * 480)::int)
from generate_series(1, 5000) g;

-- 券 600
insert into voucher (id, shop_id, title, face_value, threshold, total_stock, valid_from, valid_to)
select g,
       1 + floor(random() * 200)::int,
       '代金券' || g,
       (array[10,20,50,100])[1 + floor(random() * 4)::int],
       (array[0,50,100,200])[1 + floor(random() * 4)::int],
       1000,
       date '2026-01-01',
       date '2026-12-31'
from generate_series(1, 600) g;

-- 订单 5 万，覆盖近 6 个月。
-- 关键：随机数必须在 CTE 里按行生成一次再复用，否则同一行的 status / used_at 会互相矛盾。
-- 用 AS MATERIALIZED 是为了阻止 PG 把 CTE 内联后重复求值。
with base as materialized (
    select g,
           random() as r_status,
           random() as r_voucher,
           random() as r_user,
           random() as r_day,
           random() as r_amount,
           random() as r_extra_day
    from generate_series(1, 50000) g
)
insert into voucher_order (user_id, voucher_id, shop_id, region_id, category_id,
                           pay_amount, status, created_at, paid_at, used_at, refunded_at)
select
    1 + floor(b.r_user * 5000)::int,
    v.id,
    v.shop_id,
    s.region_id,
    s.category_id,
    round((v.face_value * (0.80 + b.r_amount * 0.20))::numeric, 2),
    case when b.r_status < 0.05 then 'REFUNDED'
         when b.r_status < 0.40 then 'PAID'
         else 'USED' end,
    t.ts,
    t.ts + interval '1 minute',
    case when b.r_status >= 0.40
         then t.ts + make_interval(days => (1 + floor(b.r_extra_day * 5))::int) end,
    case when b.r_status < 0.05
         then t.ts + make_interval(days => (1 + floor(b.r_extra_day * 3))::int) end
from base b
join voucher v on v.id = 1 + floor(b.r_voucher * 600)::int
join shop s on s.id = v.shop_id
cross join lateral (
    -- 相对当前时间生成，重跑种子后数据止于最近一天；归因窗口不会因为“数据停在几天前”漂移。
    select now() - make_interval(days => 180 - floor(b.r_day * 180)::int) as ts
) t;

-- 埋一个可被归因发现的异常：华东区火锅品类最近 30 天订单量下降 65%。
-- 没有这个异常，"为什么跌了"这类问题就永远只能答"没跌"。
-- 时间窗从数据自身推出，不写死日期，重跑种子也成立。
delete from voucher_order o
using shop s
where o.shop_id = s.id
  and s.region_id between 101 and 104
  and s.category_id = 11
  and o.created_at >= (select max(created_at) - interval '30 days' from voucher_order)
  and random() < 0.45;

-- 指标口径已迁至 knowledge_doc，见 scripts/seed_knowledge.sql
