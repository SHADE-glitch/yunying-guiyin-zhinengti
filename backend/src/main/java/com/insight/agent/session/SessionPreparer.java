package com.insight.agent.session;

import com.insight.agent.attribution.AttributionScopeParser;
import com.insight.agent.attribution.AttributionScopeParser.AmbiguousScopeException;
import com.insight.agent.attribution.AttributionService.AttributionRun;
import com.insight.agent.attribution.MetricRouter;
import com.insight.agent.attribution.sql.AttributionProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 会话编排：准备（建/载会话 + 补全判定）与落账（轮次 + 状态）。
 * 归因链路之外的控制器只依赖这一个类。
 */
@Component
public class SessionPreparer {

    public record Prepared(UUID sessionId, String question, String resolved, String note) {

        /** resolved 为 null 表示：问题不够具体，应当重问。 */
        public boolean clarify() {
            return resolved == null;
        }
    }

    private final SessionService sessions;
    private final FollowUpCompleter completer;
    private final AttributionScopeParser scopeParser;
    private final AttributionProperties props;

    public SessionPreparer(SessionService sessions, FollowUpCompleter completer,
                           AttributionScopeParser scopeParser, AttributionProperties props) {
        this.sessions = sessions;
        this.completer = completer;
        this.scopeParser = scopeParser;
        this.props = props;
    }

    public Prepared prepare(String rawSessionId, String question) {
        UUID sessionId = sessions.ensure(rawSessionId);
        SessionService.State state = sessions.load(sessionId);

        Map<String, String> explicitScope = new HashMap<>();
        try {
            scopeParser.parse(question).filters()
                    .forEach((dimension, value) -> explicitScope.put(dimension.name(), value));
        } catch (AmbiguousScopeException e) {
            // 指代歧义交给服务层抛标准错误码（AMBIGUOUS_SCOPE），补全不掺和
        }

        FollowUpCompleter.Completion completion = completer.complete(
                question, state, explicitScope, MetricRouter.mentionsMetric(question));
        if (completion.kind() == FollowUpCompleter.Kind.CLARIFY) {
            return new Prepared(sessionId, question, null, completion.note());
        }
        return new Prepared(sessionId, question, completion.resolvedQuestion(), completion.note());
    }

    public void record(Prepared prepared, AttributionRun run) {
        Map<String, String> scope = new HashMap<>();
        run.rootScope().filters().forEach((dimension, value) -> scope.put(dimension.name(), value));
        sessions.record(prepared.sessionId(), prepared.question(), prepared.resolved(),
                "ATTRIBUTION", run.runId(),
                MetricRouter.route(prepared.resolved(), props.getMetric()), scope);
    }
}
