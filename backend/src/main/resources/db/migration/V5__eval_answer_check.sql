-- 查数类用例的答案校验。
--
-- 只查"有没有调工具"太松：模型完全可以查一条 SQL、然后答一句废话。加一个可选的关键词，
-- 命中才算答对。留空表示这条用例只校验路径，不校验答案内容（问题本身没有稳定关键词）。
alter table eval_agent_case add column expected_answer_contains text;
