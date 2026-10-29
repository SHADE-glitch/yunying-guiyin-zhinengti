package com.insight.agent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话状态与最近轮次。只存"追问补全需要的结构"，不存模型读回的历史结论：
 * 业务数据永远重查，旧结论不回灌——这是审计，不是记忆。
 */
@Component
public class SessionService {

    public record State(String metric, Map<String, String> scope, UUID lastRunId, int turnCount) {
    }

    public record Turn(int turnNo, String question, String resolved) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 客户端给的 sessionId 合法就复用（不存在则建），没给就新建。 */
    public UUID ensure(String requested) {
        UUID id = parse(requested);
        if (id == null) {
            id = UUID.randomUUID();
        }
        jdbc.update("""
                insert into session_state (id) values (?)
                on conflict (id) do nothing
                """, id);
        return id;
    }

    public State load(UUID sessionId) {
        return jdbc.query("select metric, scope, last_run_id, turn_count from session_state where id = ?",
                (rs, i) -> new State(
                        rs.getString("metric"),
                        parseScope(rs.getString("scope")),
                        (UUID) rs.getObject("last_run_id"),
                        rs.getInt("turn_count")),
                sessionId).stream().findFirst()
                .orElse(new State("gmv", Map.of(), null, 0));
    }

    public List<Turn> recent(UUID sessionId, int limit) {
        return jdbc.query("""
                select turn_no, question, resolved from session_turn
                 where session_id = ?
                 order by turn_no desc
                 limit ?
                """, (rs, i) -> new Turn(
                        rs.getInt("turn_no"), rs.getString("question"), rs.getString("resolved")),
                sessionId, Math.min(limit, 10));
    }

    public void record(UUID sessionId, String question, String resolved, String mode,
                       UUID runId, String metric, Map<String, String> scope) {
        String scopeJson = scope == null || scope.isEmpty() ? "{}" : toJson(scope);
        Integer turnNo = jdbc.queryForObject("""
                insert into session_turn (session_id, turn_no, question, resolved, mode, run_id)
                values (?, (select turn_count + 1 from session_state where id = ?), ?, ?, ?, ?)
                returning turn_no
                """, Integer.class, sessionId, sessionId, question, resolved, mode, runId);
        jdbc.update("""
                update session_state
                   set metric = ?, scope = ?::jsonb, last_run_id = ?, turn_count = ?, updated_at = now()
                 where id = ?
                """, metric, scopeJson, runId, turnNo == null ? 1 : turnNo, sessionId);
    }

    private String toJson(Map<String, String> scope) {
        try {
            return mapper.writeValueAsString(scope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话范围序列化失败", e);
        }
    }

    private Map<String, String> parseScope(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = mapper.readValue(json,
                    mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
            return Map.copyOf(parsed);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
