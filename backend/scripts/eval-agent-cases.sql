-- Agent 任务级评测用例（42 条：20 归因 + 12 查数 + 5 守卫 + 5 会话）。
--
-- 期望值是从种子数据算出来的，不是拍的（窗口 = 数据末端 as-of 往前 30 天，右开区间）：
-- 评测运行时会自动把 as-of 钉到 max(voucher_order.created_at)，批次里记录这个值；
-- 重跑 seed 后相对分布不变，窗口不再跟着 now() 漂。
-- 若手工核对，先查 select max(created_at) from voucher_order 作为 as-of。
--
--   华东 ~ -8%，火锅贡献主导 → 下钻到门店层分散 → MULTI_FACTOR
--   华南 ~ -7.5%，咖啡贡献主导 → 门店层分散 → MULTI_FACTOR
--   华北 ~ +13%，最大单项不到 0.6 → 第一层就分散 → MULTI_FACTOR
--   西南 ~ -5%，KTV 贡献刚好不到 0.6 → 第一层就分散 → MULTI_FACTOR
--   青岛火锅 ~ +53%，单店 商户015 贡献 1.08 → 门店层收敛 → DOMINANT_FOUND
--   南京火锅 ~ -48%，门店层分散（最大贡献 0.397）→ MULTI_FACTOR
--   全平台 ~ -2.5% → 不到 3% 门槛 → NO_SIGNIFICANT_CHANGE
--
-- 用法：psql 导入后启动批次评测（POST /api/eval/agent/runs）。
delete from eval_agent_case;

insert into eval_agent_case (question, kind, expected_outcome, expected_leaf, expected_path, note) values
-- 华东：区域 → 品类 → 火锅 → 门店层分散
('华东为什么跌了',                    'ATTRIBUTION', 'MULTI_FACTOR', '火锅', '["CATEGORY=火锅"]', '主异常：火锅 -39.6%'),
('华东 GMV 下降的原因是什么',          'ATTRIBUTION', 'MULTI_FACTOR', '火锅', '["CATEGORY=火锅"]', '换一种问法'),
('最近华东的销售额怎么回事',           'ATTRIBUTION', 'MULTI_FACTOR', '火锅', '["CATEGORY=火锅"]', '口语化问法'),
-- 华东 + 火锅 都钉死：第一层只能拆门店，且门店层是分散的
('华东火锅为什么跌了',                'ATTRIBUTION', 'MULTI_FACTOR', null, null, '两层范围都在问题里'),
('华东的火锅业务下滑是什么原因',       'ATTRIBUTION', 'MULTI_FACTOR', null, null, '两层范围都在问题里'),
('火锅在华东卖得怎么样',              'ATTRIBUTION', 'MULTI_FACTOR', null, null, '两层范围都在问题里'),
-- 华南：咖啡是主因，但咖啡内部也分散
('华南为什么跌了',                    'ATTRIBUTION', 'MULTI_FACTOR', '咖啡', '["CATEGORY=咖啡"]', '咖啡贡献 -1.06'),
('华南 GMV 下滑原因',                 'ATTRIBUTION', 'MULTI_FACTOR', '咖啡', '["CATEGORY=咖啡"]', '换一种问法'),
('华南最近生意怎么样',                'ATTRIBUTION', 'MULTI_FACTOR', '咖啡', '["CATEGORY=咖啡"]', '口语化问法'),
-- 华北：整体在涨，但没有单项达到阈值
('华北为什么涨了',                    'ATTRIBUTION', 'MULTI_FACTOR', null, null, '最大贡献 0.29 < 0.6'),
('华北 GMV 上升的原因',               'ATTRIBUTION', 'MULTI_FACTOR', null, null, '换一种问法'),
('华北最近销售额变化',                'ATTRIBUTION', 'MULTI_FACTOR', null, null, '口语化问法'),
-- 西南：跌了 5.3%，但没有任何单项达到 0.6（KTV 0.574，离门槛很近）
('西南为什么跌了',                    'ATTRIBUTION', 'MULTI_FACTOR', null, null, 'KTV 0.574，刚好不到阈值'),
('西南 GMV 下降的原因',               'ATTRIBUTION', 'MULTI_FACTOR', null, null, '换一种问法'),
-- 城市 + 品类两层范围都钉死，只剩门店层：叶子维度应在主因处收敛
('青岛火锅为什么涨了',                'ATTRIBUTION', 'DOMINANT_FOUND', '商户015', '["SHOP=商户015"]', '单店贡献 1.08，叶子维度收敛'),
('南京火锅为什么跌了',                'ATTRIBUTION', 'MULTI_FACTOR', null, null, '门店层分散，最大贡献 0.397'),
-- 全平台：变化不到 3%，不该硬归因
('最近整体 GMV 怎么样',               'ATTRIBUTION', 'NO_SIGNIFICANT_CHANGE', null, null, '+0.45%'),
('为什么平台 GMV 下降了',             'ATTRIBUTION', 'NO_SIGNIFICANT_CHANGE', null, null, '全平台口径'),
('全平台销售额有没有异常',            'ATTRIBUTION', 'NO_SIGNIFICANT_CHANGE', null, null, '全平台口径'),
('最近一个月生意如何',                'ATTRIBUTION', 'NO_SIGNIFICANT_CHANGE', null, null, '无范围词'),
-- 查数路径：必须调工具，且不能走到归因
('GMV 怎么算',                        'LOOKUP', null, null, null, '知识库'),
('退款率怎么算',                      'LOOKUP', null, null, null, '知识库'),
('客单价怎么算',                      'LOOKUP', null, null, null, '知识库'),
('什么叫核销',                        'LOOKUP', null, null, null, '知识库'),
('退款订单算不算 GMV',                'LOOKUP', null, null, null, '知识库口径'),
('订单状态有哪些',                    'LOOKUP', null, null, null, '知识库'),
('平台有哪些大区',                    'LOOKUP', null, null, null, '查数据'),
('有哪些品类',                        'LOOKUP', null, null, null, '查数据'),
('voucher_order 表有哪些字段',        'LOOKUP', null, null, null, '看 schema'),
('商户表的主键是什么',                'LOOKUP', null, null, null, '看 schema'),
-- 注意别问"华东有多少商户"：动销口径和入驻口径不一致，开放式问法会让模型一路查到底。
-- 这一条刻意问成"动销"，测的是它能不能答对，而不是能不能猜中口径。
('华东有多少动销商户',                'LOOKUP', null, null, null, '查数据（动销口径）'),
('最近 30 天的订单数是多少',          'LOOKUP', null, null, null, '查数据');
-- 答案关键词：只给"答案里几乎必然出现该词"的用例填。
-- 留空的表示只校验走了工具，因为问题本身没有稳定关键词（数字答案、泛化名词、
-- 或者模型可能用中文/枚举两种写法回答）。
update eval_agent_case set expected_answer_contains = '已支付'      where question = 'GMV 怎么算';
update eval_agent_case set expected_answer_contains = '退款订单数'  where question = '退款率怎么算';
update eval_agent_case set expected_answer_contains = '订单量'      where question = '客单价怎么算';
update eval_agent_case set expected_answer_contains = 'pay_amount'  where question = 'voucher_order 表有哪些字段';
update eval_agent_case set expected_answer_contains = '华东'        where question = '平台有哪些大区';
update eval_agent_case set expected_answer_contains = '火锅'        where question = '有哪些品类';

-- 多关键词：语义正确但字面不同的答案不该被误杀。
-- 例："退款率怎么算" 的答案可能写"退款订单数"，也可能直接写 "status = REFUNDED"。
update eval_agent_case set expected_answer_any = array['已支付', 'PAID']
 where question = 'GMV 怎么算';
update eval_agent_case set expected_answer_any = array['退款订单数', 'REFUNDED', 'refunded']
 where question = '退款率怎么算';
update eval_agent_case set expected_answer_any = array['订单量', '订单数']
 where question = '客单价怎么算';
update eval_agent_case set expected_answer_any = array['pay_amount']
 where question = 'voucher_order 表有哪些字段';
update eval_agent_case set expected_answer_any = array['华东']
 where question = '平台有哪些大区';
update eval_agent_case set expected_answer_any = array['火锅']
 where question = '有哪些品类';

-- ---- 守卫用例：期望的是错误码，不是答案 ----
insert into eval_agent_case (question, kind, expected_error, note) values
('帮我写一首诗',                'GUARD', 'OUT_OF_SCOPE',      '入口守卫：明显越界'),
('为什么退款率跌了',            'GUARD', 'UNSUPPORTED_METRIC', '指标守卫：比率指标不可加分解'),
('华东客单价为什么涨了',        'GUARD', 'UNSUPPORTED_METRIC', '指标守卫：客单价不支持'),
('华东和华南为什么 GMV 跌了',   'GUARD', 'AMBIGUOUS_SCOPE',    '范围解析：同一维度两个值不猜'),
('那家店为什么跌了',            'GUARD', 'AMBIGUOUS_SCOPE',    '指代消解：要求说具体名称');

-- ---- 会使用例：第一轮设置状态，第二轮看补全结果 ----
insert into eval_agent_case
    (question, kind, setup_question, expected_resolved_contains, expected_error, note) values
('那华南呢',                  'SESSION', '为什么华东GMV跌了',   array['华南', 'GMV'],   null,      '继承上一轮指标'),
('为什么跌了',                'SESSION', '为什么华东GMV跌了',   array['华东', 'GMV'],   null,      '继承上一轮范围与指标'),
('那华北呢',                  'SESSION', '为什么华南订单量跌了', array['华北', '订单量'], null,      '继承 order_count 指标'),
('嗯',                        'SESSION', '为什么华东GMV跌了',   null,                   'CLARIFY', '问得太短且无延续词，要求重问'),
('华南订单量为什么跌了',      'SESSION', '为什么华东GMV跌了',   array['华南', '订单量'], null,      '点名新指标时不继承旧指标');
