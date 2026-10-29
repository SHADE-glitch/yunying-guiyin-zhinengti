package com.insight.agent.attribution.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.attribution.ContributionMath.Split;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.attribution.Evidence;
import com.insight.agent.attribution.sql.QueryWindow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 归因树落库。
 *
 * <p>写两类节点：结论路径（ROOT / DOMINANT），和被淘汰的候选（PROBED）。
 * 为什么要写被淘汰的：只留主因，"为什么不是别的"就没有证据。面试里被问
 * "你怎么证明是这个原因"，答案应该是"同一层的其他候选贡献度是这些"。
 *
 * <p>每条节点都带 query_hash 和 evidence_step_id。SQL 原文只在 agent_step 里存一份，
 * 写之前先用 {@link EvidenceBinding} 校验指纹，避免写进一条回溯不了的结论。
 */
@Component
public class AttributionNodeWriter {

    private final JdbcTemplate jdbc;
    private final EvidenceBinding binding;
    private final ObjectMapper mapper;

    public AttributionNodeWriter(JdbcTemplate jdbc, EvidenceBinding binding, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.binding = binding;
        this.mapper = mapper;
    }

    public long writeRoot(UUID runId, Totals root, Evidence evidence, Scope scope,
                          QueryWindow window, String metric) {
        return insert(runId, null, 0, null, null, scope, window,
                root.current(), root.baseline(), root.delta(), null, "ROOT", bind(evidence), evidence, metric);
    }

    /**
     * 写一次拆分的全部候选，返回被选中那一行的 id（下一层拿它当 parent）。
     *
     * @param selected 可以为空：这一层没有单项达到阈值时，所有候选都记成 PROBED
     */
    public long writeSplit(UUID runId, long parentId, int depth, Dimension dimension, Scope scope,
                           Split split, Scored selected, Evidence evidence, QueryWindow window,
                           String metric) {
        Long stepId = bind(evidence);
        long selectedId = 0L;
        for (Scored item : split.items()) {
            boolean isSelected = selected != null && item.value().equals(selected.value());
            long id = insert(runId, parentId, depth, dimension, item.value(), scope, window,
                    item.current(), item.baseline(), item.delta(), item.contribution(),
                    isSelected ? "DOMINANT" : "PROBED", stepId, evidence, metric);
            if (isSelected) {
                selectedId = id;
            }
        }
        return selectedId;
    }

    /** 校验并返回可写入的 step id。没有证据（或证据未绑定步骤）时写 null，不阻断落库。 */
    private Long bind(Evidence evidence) {
        if (evidence == null || evidence.stepId() == null) {
            return null;
        }
        binding.verify(evidence.stepId(), evidence);
        return evidence.stepId();
    }

    private long insert(UUID runId, Long parentId, int depth, Dimension dimension, String targetValue,
                        Scope scope, QueryWindow window, BigDecimal current, BigDecimal baseline,
                        BigDecimal delta, BigDecimal contribution, String verdict, Long evidenceStepId,
                        Evidence evidence, String metric) {
        Long id = jdbc.queryForObject("""
                insert into attribution_node (run_id, parent_id, depth, dimension, target_value, scope,
                                              metric_code, window_start, window_end, baseline_start, baseline_end,
                                              current_value, baseline_value, delta, contribution, verdict,
                                              evidence_step_id, query_hash, row_count)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict on constraint uk_node_parent_dim_value do update set
                    current_value    = excluded.current_value,
                    baseline_value   = excluded.baseline_value,
                    delta            = excluded.delta,
                    contribution     = excluded.contribution,
                    verdict          = excluded.verdict,
                    evidence_step_id = excluded.evidence_step_id,
                    query_hash       = excluded.query_hash,
                    row_count        = excluded.row_count
                returning id
                """, Long.class,
                runId, parentId, depth,
                dimension == null ? null : dimension.name(), targetValue, scopeJson(scope),
                metric,
                ts(window.currentStart()), ts(window.currentEnd()),
                ts(window.baselineStart()), ts(window.baselineEnd()),
                current, baseline, delta, contribution, verdict,
                evidenceStepId,
                evidence == null ? null : evidence.queryHash(),
                evidence == null ? null : evidence.rowCount());
        return id == null ? 0L : id;
    }

    private String scopeJson(Scope scope) {
        Map<String, String> raw = new LinkedHashMap<>();
        scope.filters().forEach((dimension, value) -> raw.put(dimension.name(), value));
        try {
            return mapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("归因范围序列化失败", e);
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
