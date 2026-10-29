package com.insight.agent.api;

import com.insight.agent.rag.KnowledgeSearchService;
import com.insight.agent.rag.KnowledgeSearchService.Mode;
import com.insight.agent.rag.SearchHit;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 展示层：知识库（RAG 语料）的只读查询与检索演示。
 *
 * <p>带 {@code q} 时走和工具内完全相同的 {@code KnowledgeSearchService}（HYBRID = 向量 + trgm + RRF），
 * 页面上看到的命中就是 Agent 实际检索到的东西，不是另一套演示逻辑。
 */
@RestController
public class KnowledgeController {

    public record Doc(long id, String docType, String code, String title, String content,
                      List<String> synonyms, String sqlTemplate, List<String> relatedTables,
                      Double score) {
    }

    private final JdbcTemplate jdbc;
    private final KnowledgeSearchService search;

    public KnowledgeController(JdbcTemplate jdbc, KnowledgeSearchService search) {
        this.jdbc = jdbc;
        this.search = search;
    }

    @GetMapping("/api/knowledge")
    public Map<String, Object> knowledge(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 语料统计放这里：评测端点要真 key 才能跑，无 key 时页面至少能看到向量补齐情况
        body.put("total", search.totalCount());
        body.put("embedded", search.embeddedCount());
        if (q != null && !q.isBlank()) {
            String query = q.trim();
            List<SearchHit> hits;
            try {
                hits = search.search(query, Math.min(limit, 50), Mode.HYBRID);
                body.put("mode", "HYBRID");
            } catch (RuntimeException e) {
                // 向量路要现调 embedding API；无 key 演示时降级关键词路并如实标注，
                // 不静默换路：mode 字段就是给前端展示"这次是哪条路"的
                hits = search.search(query, Math.min(limit, 50), Mode.KEYWORD);
                body.put("mode", "KEYWORD");
                body.put("fallback", "向量路不可用（embedding 服务未配置），本次仅关键词路");
            }
            body.put("query", query);
            body.put("docs", hits.stream()
                    .map(h -> new Doc(h.id(), h.docType(), null, h.title(), h.content(),
                            List.of(), null, List.of(), h.score()))
                    .toList());
            return body;
        }
        body.put("mode", "LIST");
        body.put("docs", jdbc.query("""
                select id, doc_type, code, title, content, synonyms, sql_template, related_tables
                  from knowledge_doc
                 where (?::varchar is null or doc_type = ?)
                 order by id
                 limit ?
                """, (rs, i) -> new Doc(
                        rs.getLong("id"), rs.getString("doc_type"), rs.getString("code"),
                        rs.getString("title"), rs.getString("content"),
                        textArray(rs, "synonyms"), rs.getString("sql_template"),
                        textArray(rs, "related_tables"), null),
                type, type, Math.min(limit, 200)));
        return body;
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
