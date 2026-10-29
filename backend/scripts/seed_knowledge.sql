-- 知识库与评测集。60 篇文档 + 40 条标注查询。
-- 文档覆盖三类：指标口径（METRIC）、平台规则（RULE）、常见问题（FAQ）。
-- 评测集的价值不在数量，在于每条都标了"应该召回哪几篇"——没有标注，recall@5 就是个空词。

select setseed(0.42);
truncate eval_case, knowledge_doc restart identity cascade;

insert into knowledge_doc (id, doc_type, code, title, content, synonyms, sql_template, related_tables) values
(1,'METRIC','gmv','GMV','平台成交总额。口径为已支付订单的实付金额之和，含后续退款的订单（退款单不做负值冲减）。不含未支付订单。',array['销售额','成交额','交易额','流水'],'select sum(pay_amount) from voucher_order where status in (''PAID'',''USED'',''REFUNDED'') and created_at >= :start and created_at < :end',array['voucher_order']),
(2,'METRIC','order_count','订单量','已支付订单数。口径为 status 属于 PAID/USED/REFUNDED 的订单条数。',array['单量','订单数','成交单数'],'select count(*) from voucher_order where status in (''PAID'',''USED'',''REFUNDED'') and created_at >= :start and created_at < :end',array['voucher_order']),
(3,'METRIC','avg_order_value','客单价','GMV 除以订单量。分母是已支付订单数，不是访问用户数，也不是下单用户数。',array['均价','平均订单金额','AOV'],'select sum(pay_amount)/nullif(count(*),0) from voucher_order where status in (''PAID'',''USED'',''REFUNDED'')',array['voucher_order']),
(4,'METRIC','refund_rate','退款率','退款订单数除以已支付订单数。分母口径与 GMV 一致，分子是 status=REFUNDED。',array['退货率','退款比例'],'select count(*) filter (where status=''REFUNDED'')::numeric / nullif(count(*),0) from voucher_order',array['voucher_order']),
(5,'METRIC','redemption_rate','核销率','已核销订单数除以已支付订单数。核销指 used_at 非空，不是指券被领取。',array['使用率','到店率'],'select count(*) filter (where used_at is not null)::numeric / nullif(count(*),0) from voucher_order',array['voucher_order']),
(6,'METRIC','active_users','活跃用户数','周期内产生过已支付订单的去重用户数。只看下单，不看浏览和登录。',array['活跃人数','DAU','下单用户数'],'select count(distinct user_id) from voucher_order where status in (''PAID'',''USED'',''REFUNDED'')',array['voucher_order']),
(7,'METRIC','repurchase_rate','复购率','周期内下单次数大于等于 2 的用户数，除以该周期内的下单用户数。默认按自然月统计。',array['回购率','二次购买率'],null,array['voucher_order']),
(8,'METRIC','new_user_ratio','新客占比','周期内首次下单的用户数除以该周期下单用户数。首次指全历史第一次，不是本周期第一次。',array['新用户占比','新客比例'],null,array['voucher_order','app_user']),
(9,'METRIC','gmv_mom','GMV 环比','本期 GMV 与上一等长周期的比值变化。环比按自然月对齐，不是按 30 天滚动。',array['环比','月度增长'],null,array['voucher_order']),
(10,'METRIC','conversion_rate','转化率','下单用户数除以访问用户数。访问数据不在本库，当前无法计算。',array['转化','成交转化率'],null,array['voucher_order']),
(11,'METRIC','voucher_claim_rate','券领取率','已领取券数除以发放券数。发放数是券库存被曝光的总量。',array['领取率','领券率'],null,array['voucher']),
(12,'METRIC','voucher_use_rate','券使用率','已核销券数除以已领取券数。和核销率不同，分母是领取不是支付。',array['用券率'],null,array['voucher','voucher_order']),
(13,'METRIC','voucher_expire_rate','券过期率','过期未使用券数除以已领取券数。过期指超过 valid_to 且未核销。',array['过期率'],null,array['voucher']),
(14,'METRIC','refund_amount','退款金额','退款订单的实付金额之和。退款金额不计入 GMV 的负向冲减，两个指标各自独立。',array['退款额','退单金额'],'select sum(pay_amount) from voucher_order where status=''REFUNDED''',array['voucher_order']),
(15,'METRIC','refund_duration','退款时长','从发起退款的 refunded_at 到退款完成的平均耗时，单位小时。',array['退款耗时','到账时长'],null,array['voucher_order']),
(16,'METRIC','complaint_rate','投诉率','投诉工单数除以订单数。工单数据不在本库，当前无法计算。',array['投诉比例'],null,array['voucher_order']),
(17,'METRIC','fulfillment_rate','履约率','已核销订单数除以应履约订单数。应履约指已支付且未退款。',array['履约','到店履约率'],null,array['voucher_order']),
(18,'METRIC','discount_rate','折扣率','优惠金额除以原价金额。优惠金额指面值与实付的差额。',array['折扣力度','让利率'],null,array['voucher_order','voucher']),
(19,'METRIC','active_shop_count','动销商户数','周期内至少有一笔已支付订单的商户数。有曝光无成交不算动销。',array['有效商户数','出单商户数'],null,array['shop','voucher_order']),
(20,'METRIC','avg_orders_per_shop','单商户均单量','订单量除以动销商户数。分母是动销商户，不是全部商户。',array['户均单量'],null,array['shop','voucher_order']),
(21,'METRIC','exposure_click_rate','曝光点击率','点击次数除以曝光次数。曝光数据不在本库，当前无法计算。',array['点击率','CTR'],null,array['voucher']),
(22,'METRIC','gmv_per_user','人均消费','GMV 除以活跃用户数。和客单价不同，分母是人不是单。',array['ARPU','人均GMV'],null,array['voucher_order']),
(23,'METRIC','weekend_gmv_ratio','周末 GMV 占比','周六周日产生的 GMV 除以周期总 GMV。',array['周末占比'],null,array['voucher_order']),
(24,'METRIC','category_share','品类占比','某品类 GMV 除以总 GMV。品类按二级品类归集，一级品类需向上汇总。',array['品类结构','类目占比'],null,array['category','voucher_order'])
;

insert into knowledge_doc (id, doc_type, code, title, content, synonyms, sql_template, related_tables) values
(25,'RULE',null,'券叠加规则','同一订单只能使用一张代金券，不支持跨券叠加。平台券和商户券互斥，优先使用面值高的。',array['叠加','多张券','同时用'],null,'{}'),
(26,'RULE',null,'退款规则','已支付未核销的订单可全额退款；已核销订单不支持自助退款，需商户协商。退款原路返回。',array['退单','申请退款'],null,'{}'),
(27,'RULE',null,'核销规则','到店出示券码，商户扫码后置为已核销并写入 used_at。核销后不可撤销，只能走退款流程。',array['验券','销券'],null,'{}'),
(28,'RULE',null,'券有效期规则','券的有效期由 valid_from 和 valid_to 决定，按自然日计算，含首尾两天。过期后不可核销也不可延期。',array['到期','过期'],null,'{}'),
(29,'RULE',null,'跨店券规则','跨店券可在同一品牌下多家门店使用，但仅限同城。跨城市使用会被拦截。',array['通用券','多店可用'],null,'{}'),
(30,'RULE',null,'秒杀规则','秒杀券库存独立于普通券，采用 Redis 预扣加异步落单。超卖由 Lua 脚本保证不发生。',array['抢购','限时'],null,'{}'),
(31,'RULE',null,'商户结算周期','商户结算按 T+7 执行，即订单核销后第 7 个自然日打款。退款订单从当期结算中扣除。',array['打款','结账'],null,'{}'),
(32,'RULE',null,'发票开具规则','用户可在订单完成后 90 天内申请电子发票，由商户开具。平台不代开。',array['开票','发票'],null,'{}'),
(33,'RULE',null,'数据口径优先级','同一指标出现多个口径时，以 knowledge_doc 中的 METRIC 文档为准，其次以财务口径为准。两者冲突需上报数据组。',array['口径不一致','以哪个为准'],null,'{}'),
(34,'RULE',null,'区域划分规则','区域分两级：大区（华东华南华北西南）和城市。订单表存的是城市级 region_id，大区需要按 parent_id 向上归集。',array['大区','城市归属'],null,'{}'),
(35,'RULE',null,'品类归属规则','品类分两级，商户只挂在二级品类下。按一级品类统计时需要对子品类求和，不能直接匹配商户的 category_id。',array['类目归属'],null,'{}'),
(36,'RULE',null,'订单状态流转','订单状态为 PAID（已支付）、USED（已核销）、REFUNDED（已退款）三态。不存在待支付状态，未支付订单不落库。',array['订单状态','状态机'],null,'{}'),
(37,'RULE',null,'部分退款规则','当前不支持部分退款，一张券只能整单退。需要部分退款时由客服线下处理并登记。',array['退一部分'],null,'{}'),
(38,'RULE',null,'退款审核时效','自动退款即时到账；人工审核的退款在 24 小时内处理，超时自动通过。',array['审核时间','多久到账'],null,'{}'),
(39,'RULE',null,'商户入驻审核','商户提交资质后 3 个工作日内完成审核，需营业执照、法人身份证、门店照片。',array['入驻','开店审核'],null,'{}'),
(40,'RULE',null,'券库存规则','券库存扣减发生在支付成功时，不是领取时。库存为 0 后不再展示，已领取的券不受影响。',array['库存','卖完了'],null,'{}'),
(41,'RULE',null,'用户等级规则','用户等级按近 12 个月累计消费金额划分，共 5 级。等级每月 1 日更新。',array['会员等级','VIP'],null,'{}'),
(42,'RULE',null,'积分规则','消费 1 元累计 1 积分，退款时扣回。积分不可提现，仅可抵扣。',array['积分','points'],null,'{}'),
(43,'RULE',null,'风控拦截规则','同一设备 24 小时内下单超过 20 笔触发风控，订单进入人工复核。被拦截订单不计入 GMV。',array['风控','异常订单'],null,'{}'),
(44,'RULE',null,'数据延迟说明','订单数据实时写入，报表层 T+1 汇总。当日数据在 02:00 前完成结算，02:00 后查询前一自然日才准确。',array['数据延迟','T+1'],null,'{}'),
(45,'FAQ',null,'为什么 GMV 涨了订单量没涨','通常是客单价上升导致：高面值券占比提高，或大额订单集中。按品类拆分看是否有单一品类拉动。',array['GMV涨订单不涨'],null,'{}'),
(46,'FAQ',null,'退款后 GMV 会减少吗','不会。GMV 口径包含退款订单，不做负向冲减。要看净成交请用 GMV 减去退款金额。',array['退款影响GMV吗'],null,'{}'),
(47,'FAQ',null,'核销率低说明什么','可能是券面值吸引力不足、有效期太短、或商户覆盖不够。先按城市和品类拆分，再看领取到核销的转化。',array['核销低'],null,'{}'),
(48,'FAQ',null,'客单价突然升高怎么排查','先看是否有大额券集中核销，再按品类拆分确认是否单一品类拉动，最后排除异常订单（风控未拦截的大额单）。',array['客单价异常'],null,'{}'),
(49,'FAQ',null,'为什么某城市订单量骤降','依次检查：商户是否下架、券库存是否耗尽、是否有风控拦截、是否有区域性活动结束。',array['订单骤降','暴跌'],null,'{}'),
(50,'FAQ',null,'新客占比怎么算才准','必须用全历史首次下单判定，不能用本周期首次。用 app_user.registered_at 会高估新客。',array['新客不准'],null,'{}'),
(51,'FAQ',null,'券领取率低的原因','曝光不足或面值缺乏吸引力。先看曝光量是否下降，再看同类券的历史领取率。',array['领取率低'],null,'{}'),
(52,'FAQ',null,'复购率按什么周期算','默认自然月。跨月复购需要按月滚动计算，不能简单用总订单数除以总用户数。',array['复购周期'],null,'{}'),
(53,'FAQ',null,'商户数为什么和后台不一致','报表只统计动销商户，后台显示全部入驻商户。有曝光无成交的商户不计入。',array['商户数不一致'],null,'{}'),
(54,'FAQ',null,'数据为什么对不上财务','平台 GMV 含退款订单，财务确认收入按净额。两边口径不同，不是数据错误。',array['对不上账','财务差异'],null,'{}'),
(55,'FAQ',null,'时区问题怎么处理','所有时间字段为 timestamptz，按数据库默认时区存储。跨时区统计时显式指定时区，不要依赖服务器本地时间。',array['时区','UTC'],null,'{}'),
(56,'FAQ',null,'测试数据会不会混进来','不会。测试环境与生产库物理隔离，报表只连生产只读库。',array['测试数据'],null,'{}'),
(57,'FAQ',null,'如何按品类下钻','从一级品类汇总到二级品类，注意商户只挂二级品类，需要 join category 的 parent_id 向上归集。',array['品类拆分','下钻'],null,'{}'),
(58,'FAQ',null,'如何看同比','同比需要去年同期数据，当前库只有近 6 个月，无法计算同比，只能算环比。',array['同比','YoY'],null,'{}'),
(59,'FAQ',null,'数据多久更新一次','订单实时写入，报表 T+1。当日查询建议用实时表，历史分析用汇总表。',array['更新频率'],null,'{}'),
(60,'FAQ',null,'为什么某个商户查不到','可能原因：商户已下架、未通过审核、或该商户没有任何订单（报表只含动销商户）。',array['查不到商户'],null,'{}')
;

insert into eval_case (query, expected_doc_ids, note) values
('销售额怎么算',array[1],'核心指标'),
('GMV 包含退款订单吗',array[1,46],'跨文档：口径+FAQ'),
('客单价的分母是订单数还是用户数',array[3],'易错点'),
('退款率怎么算',array[4],null),
('核销率是什么意思',array[5],null),
('活跃用户数怎么统计',array[6],null),
('复购率按什么周期算',array[7,52],'指标+FAQ'),
('新客占比怎么算才准',array[8,50],'指标+FAQ'),
('GMV 环比怎么算',array[9],null),
('券领取率',array[11],null),
('券使用率和核销率有什么区别',array[12,5],'两个指标对比'),
('券过期率怎么算',array[13],null),
('退款金额统计口径',array[14],null),
('退款一般多久到账',array[26,38],'规则+时效'),
('履约率',array[17],null),
('折扣率怎么算',array[18],null),
('动销商户数是什么',array[19],null),
('单商户平均订单量',array[20],null),
('人均消费和客单价有什么区别',array[22,3],'两个指标对比'),
('品类占比怎么算',array[24],null),
('优惠券可以叠加使用吗',array[25],'高频问题'),
('退款规则是什么',array[26],null),
('核销后还能退吗',array[26,27],'规则组合'),
('券过期了还能用吗',array[28],null),
('跨店券能用吗',array[29],null),
('秒杀券会不会超卖',array[30],null),
('商户什么时候结算',array[31],null),
('怎么开发票',array[32],null),
('两个指标口径不一致以哪个为准',array[33],null),
('区域是怎么划分的',array[34],null),
('品类归属规则是什么',array[35],null),
('订单状态有哪几种',array[36],null),
('支持部分退款吗',array[37],null),
('退款审核要多久',array[38],null),
('券的库存是怎么扣的',array[40],null),
('GMV 涨了但订单量没涨',array[45],'归因类问题'),
('退款后 GMV 会减少吗',array[46],null),
('数据什么时候更新',array[44,59],'规则+FAQ'),
('为什么某个商户查不到',array[60],null),
('能看同比吗',array[58],null)
;

-- 校验：文档数应为 60，评测用例应为 40
do $$
declare
    doc_count int;
    case_count int;
begin
    select count(*) into doc_count from knowledge_doc;
    select count(*) into case_count from eval_case;
    if doc_count <> 60 or case_count <> 40 then
        raise exception '知识库数量不符：docs=%, cases=%', doc_count, case_count;
    end if;
end $$;
