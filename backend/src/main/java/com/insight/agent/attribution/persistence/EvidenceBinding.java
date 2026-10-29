package com.insight.agent.attribution.persistence;

import com.insight.agent.attribution.Evidence;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 证据绑定的第二道校验。
 *
 * <p>第一道是数据库外键 {@code attribution_node.evidence_step_id → agent_step(id)}，
 * 它只能保证"这一行存在"。能指向一个不存在的步骤是假的，但指向一个**别的**步骤同样是假的，
 * 所以写入前还要比对指纹：步骤里记录的 SQL 必须和证据的 sha256 一致。
 *
 * <p>比对的是 SQL 原文的指纹，不是行数或时间——原文才是结论的来源。
 * {@code query_hash} 单独存在节点上是为了事后能一眼看出"这条结论的查询有没有变过"，
 * 它不能替代这次校验：自证不算证。
 */
@Component
public class EvidenceBinding {

    private final JdbcTemplate jdbc;

    public EvidenceBinding(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 校验证据与步骤一致，不一致就抛异常——宁可写不进去，也不写一条无法回溯的结论。
     */
    public void verify(long stepId, Evidence evidence) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select input->>'sql' as sql from agent_step where id = ?", stepId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("证据指向的步骤不存在: " + stepId);
        }
        Object recorded = rows.get(0).get("sql");
        if (recorded == null) {
            throw new IllegalStateException("步骤 " + stepId + " 没有记录 SQL，不能作为归因证据");
        }
        String expected = Evidence.hash(String.valueOf(recorded));
        if (!expected.equals(evidence.queryHash())) {
            throw new IllegalStateException(
                    "证据指纹与步骤 " + stepId + " 记录的 SQL 不一致，拒绝写入无法回溯的结论");
        }
    }
}
