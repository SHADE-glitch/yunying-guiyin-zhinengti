package com.insight.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentLoop;
import com.insight.agent.agent.AgentProperties;
import com.insight.agent.agent.AgentRunResult;
import com.insight.agent.attribution.AttributionMetricGuard;
import com.insight.agent.attribution.AttributionScopeParser;
import com.insight.agent.attribution.AttributionScopeParser.AmbiguousScopeException;
import com.insight.agent.attribution.AttributionService.AttributionRun;
import com.insight.agent.attribution.Findings;
import com.insight.agent.attribution.MetricRouter;
import com.insight.agent.attribution.UnsupportedMetricException;
import com.insight.agent.attribution.sql.AttributionProperties;
import com.insight.agent.guard.OutOfScopeGuard;
import com.insight.agent.session.SessionPreparer;
import com.insight.agent.session.SessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 执行一批 Agent 评测。
 *
 * <p>评测不是"再跑一遍"，而是把环境冻在一行批次里：as-of、模型、prompt 指纹、
 * 配置覆盖、数据集版本。同一批里任何一条用例失败都不会中断整批——失败本身是
 * 需要被记录的数据，不是异常。
 */
@Component
public class AgentEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentEvalRunner.class);

    public record Prepared(
            AgentEvalRequest request,
            AttributionProperties props,
            EvalPromptLibrary.Prompt proposer,
            EvalPromptLibrary.Prompt reporter,
            EvalDatasetService.Dataset<EvalDatasetService.AgentCase> dataset,
            EvalMetadata metadata) {
    }

    private final EvalDatasetService datasets;
    private final EvalStore store;
    private final EvalPromptLibrary prompts;
    private final VariantAttributionServiceFactory attributionFactory;
    private final AgentLoopFactory loopFactory;
    private final OutOfScopeGuard guard;
    private final AttributionScopeParser scopeParser;
    private final SessionPreparer sessionPreparer;
    private final SessionService sessions;
    private final AttributionProperties defaultProps;
    private final AgentProperties defaultAgentProps;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String model;
    private final String embeddingModel;
    private final String envGitSha;

    public AgentEvalRunner(EvalDatasetService datasets, EvalStore store, EvalPromptLibrary prompts,
                           VariantAttributionServiceFactory attributionFactory,
                           AgentLoopFactory loopFactory, OutOfScopeGuard guard,
                           AttributionScopeParser scopeParser, SessionPreparer sessionPreparer,
                           SessionService sessions, AttributionProperties defaultProps,
                           AgentProperties defaultAgentProps, JdbcTemplate jdbc, ObjectMapper mapper,
                           @Value("${spring.ai.openai.chat.options.model:unknown}") String model,
                           @Value("${spring.ai.openai.embedding.options.model:unknown}") String embeddingModel,
                           @Value("${GIT_SHA:unknown}") String envGitSha) {
        this.datasets = datasets;
        this.store = store;
        this.prompts = prompts;
        this.attributionFactory = attributionFactory;
        this.loopFactory = loopFactory;
        this.guard = guard;
        this.scopeParser = scopeParser;
        this.sessionPreparer = sessionPreparer;
        this.sessions = sessions;
        this.defaultProps = defaultProps;
        this.defaultAgentProps = defaultAgentProps;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.envGitSha = envGitSha;
    }

    public Prepared prepare(AgentEvalRequest raw) {
        AgentEvalRequest request = raw == null ? AgentEvalRequest.empty() : raw;
        request.validate();

        AttributionProperties props = defaultProps.copy();
        if (request.threshold() != null) {
            props.setThreshold(request.threshold());
        }
        if (request.maxProbes() != null) {
            props.setMaxProbes(request.maxProbes());
        }
        if (request.maxDepth() != null) {
            props.setMaxDepth(request.maxDepth());
        }
        if (request.queryBudget() != null) {
            props.setQueryBudget(request.queryBudget());
        }
        if (request.tokenBudget() != null) {
            props.setTokenBudget(request.tokenBudget());
        }
        if (request.modelProposerEnabled() != null) {
            props.setModelProposerEnabled(request.modelProposerEnabled());
        }
        Instant asOf = request.asOf() != null ? request.asOf() : datasets.dataAsOf();
        props.setAsOf(asOf);
        props.validate();

        EvalPromptLibrary.Prompt proposer = prompts.proposer(request.proposerVariant());
        EvalPromptLibrary.Prompt reporter = prompts.reporter(request.reporterVariant());
        EvalDatasetService.Dataset<EvalDatasetService.AgentCase> dataset = datasets.agentDataset();
        if (dataset.size() == 0) {
            throw new IllegalArgumentException("Agent 评测集为空，先导入 scripts/eval-agent-cases.sql");
        }

        int lookupMaxSteps = request.maxSteps() != null ? request.maxSteps() : defaultAgentProps.getMaxSteps();
        int lookupTokens = request.lookupTokenBudget() != null
                ? request.lookupTokenBudget() : defaultAgentProps.getTokenBudget();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("metric", props.getMetric());
        config.put("windowDays", props.getWindowDays());
        config.put("asOf", asOf.toString());
        config.put("maxDepth", props.getMaxDepth());
        config.put("maxProbes", props.getMaxProbes());
        config.put("queryBudget", props.getQueryBudget());
        config.put("tokenBudget", props.getTokenBudget());
        config.put("threshold", props.getThreshold());
        config.put("minChangeRatio", props.getMinChangeRatio());
        config.put("minChangeAbsolute", props.getMinChangeAbsolute());
        config.put("tolerance", props.getTolerance());
        config.put("invalidProposalLimit", props.getInvalidProposalLimit());
        config.put("dimensionFailureLimit", props.getDimensionFailureLimit());
        config.put("topN", props.getTopN());
        config.put("modelProposerEnabled", props.isModelProposerEnabled());
        config.put("lookupMaxSteps", lookupMaxSteps);
        config.put("lookupTokenBudget", lookupTokens);

        String gitSha = request.gitSha() == null || request.gitSha().isBlank()
                ? envGitSha : request.gitSha().strip();
        EvalMetadata metadata = new EvalMetadata(
                gitSha, model, embeddingModel, asOf, Map.copyOf(config),
                prompts.hashes(proposer.name(), reporter.name()),
                dataset.version(), dataset.size());

        return new Prepared(request, props, proposer, reporter, dataset, metadata);
    }

    public void execute(UUID batchId, Prepared prepared) {
        AttributionServiceHolder holder = new AttributionServiceHolder(
                attributionFactory.create(prepared.props(), prepared.proposer(), prepared.reporter()),
                loopFactory.create(prepared.request().maxSteps(), prepared.request().lookupTokenBudget()));
        List<EvalCaseResult> results = new ArrayList<>();
        int done = 0;
        try {
            for (EvalDatasetService.AgentCase evalCase : prepared.dataset().cases()) {
                long start = System.currentTimeMillis();
                EvalCaseResult result;
                try {
                    result = runCase(evalCase, prepared, holder);
                } catch (RuntimeException e) {
                    log.warn("评测用例异常: {} ({})", evalCase.question(), evalCase.kind(), e);
                    result = exceptionResult(evalCase, e, System.currentTimeMillis() - start);
                }
                results.add(result);
                store.saveCaseResult(batchId, result);
                done++;
                store.updateProgress(batchId, done, prepared.dataset().size());
            }
            AgentEvalSummary summary = AgentEvalSummary.from(results);
            store.finish(batchId, "SUCCEEDED", mapper.convertValue(summary,
                    new TypeReference<Map<String, Object>>() { }), null);
        } catch (RuntimeException e) {
            log.error("评测批次失败: {}", batchId, e);
            AgentEvalSummary summary = results.isEmpty() ? null : AgentEvalSummary.from(results);
            store.finish(batchId, "FAILED", summary == null ? Map.of() : mapper.convertValue(summary,
                    new TypeReference<Map<String, Object>>() { }), e.getMessage());
        }
    }

    private EvalCaseResult runCase(EvalDatasetService.AgentCase evalCase, Prepared prepared,
                                   AttributionServiceHolder holder) {
        long start = System.currentTimeMillis();
        return switch (evalCase.kind()) {
            case "ATTRIBUTION" -> attributionCase(evalCase, prepared, holder, start);
            case "LOOKUP" -> lookupCase(evalCase, holder, start);
            case "GUARD" -> guardCase(evalCase, prepared, start);
            case "SESSION" -> sessionCase(evalCase, prepared, start);
            default -> throw new IllegalStateException("未知用例类型: " + evalCase.kind());
        };
    }

    private EvalCaseResult attributionCase(EvalDatasetService.AgentCase evalCase, Prepared prepared,
                                           AttributionServiceHolder holder, long start) {
        AttributionRun run = holder.attribution().run(evalCase.question(), event -> { });
        String outcome = run.result().outcome().name();
        List<String> actualPath = run.result().path().stream()
                .map(node -> node.dimension().name() + "=" + node.value())
                .toList();
        boolean outcomeMatch = AgentEvalScorer.outcomeMatches(evalCase.expectedOutcome(), outcome);
        boolean pathMatch = AgentEvalScorer.pathMatches(evalCase.expectedPath(), actualPath);
        boolean leafMatch = AgentEvalScorer.leafMatches(evalCase.expectedLeaf(), actualPath);
        boolean passed = outcomeMatch && pathMatch && leafMatch;
        RunMetrics metrics = runMetrics(run.runId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", actualPath);
        detail.put("outcome", outcome);
        detail.put("message", run.result().message());
        detail.put("probes", run.result().probes());
        detail.put("invalidProposals", run.result().invalidProposals());
        detail.put("dominantIsAggregate", run.result().dominantIsAggregate());
        detail.put("degraded", run.degraded());
        detail.put("factStats", factStats(run.findings()));
        detail.put("asOf", prepared.metadata().asOf().toString());

        return new EvalCaseResult(evalCase.id(), evalCase.question(), "ATTRIBUTION",
                evalCase.expectedOutcome(), outcome, passed, outcomeMatch, pathMatch, leafMatch,
                true, metrics.steps(), run.result().probes(), metrics.tokens(), run.degraded(),
                !outcomeMatch ? "OUTCOME" : !pathMatch ? "PATH" : !leafMatch ? "LEAF" : null,
                run.runId(), duration(start), detail);
    }

    private EvalCaseResult lookupCase(EvalDatasetService.AgentCase evalCase,
                                      AttributionServiceHolder holder, long start) {
        AgentRunResult result = holder.loop().run(evalCase.question(), event -> { });
        boolean usedTools = result.succeeded() && result.steps() >= 2;
        boolean answerMatch = answerMatches(evalCase.expectedAnswerAny(), result.answer());
        boolean passed = usedTools && answerMatch;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("answer", truncate(result.answer()));
        detail.put("error", result.error());
        detail.put("steps", result.steps());
        detail.put("promptTokens", result.promptTokens());
        detail.put("completionTokens", result.completionTokens());

        String actual = result.succeeded() ? "SUCCEEDED" : "FAILED(" + result.error() + ")";
        String failure = !result.succeeded() ? "RUN_FAILED"
                : result.steps() < 2 ? "NO_TOOL"
                : !answerMatch ? "ANSWER_MISS" : null;
        return new EvalCaseResult(evalCase.id(), evalCase.question(), "LOOKUP",
                "SUCCEEDED", actual, passed, true, true, true, answerMatch,
                result.steps(), 0, result.promptTokens() + result.completionTokens(), false,
                failure, result.runId(), duration(start), detail);
    }

    private EvalCaseResult guardCase(EvalDatasetService.AgentCase evalCase, Prepared prepared,
                                     long start) {
        String expected = evalCase.expectedError();
        String actual;
        String reason = null;
        switch (expected == null ? "" : expected) {
            case "OUT_OF_SCOPE", "CLARIFY" -> {
                OutOfScopeGuard.Verdict verdict = guard.classify(evalCase.question());
                actual = verdict.intent().name();
                reason = verdict.reason();
            }
            case "UNSUPPORTED_METRIC" -> {
                String metric = MetricRouter.route(evalCase.question(), prepared.props().getMetric());
                try {
                    AttributionMetricGuard.check(evalCase.question(), metric);
                    actual = "NO_ERROR";
                } catch (UnsupportedMetricException e) {
                    actual = "UNSUPPORTED_METRIC";
                    reason = e.getMessage();
                }
            }
            case "AMBIGUOUS_SCOPE" -> {
                try {
                    scopeParser.parse(evalCase.question());
                    actual = "NO_ERROR";
                } catch (AmbiguousScopeException e) {
                    actual = "AMBIGUOUS_SCOPE";
                    reason = e.getMessage();
                }
            }
            default -> {
                actual = "UNKNOWN_EXPECTATION";
                reason = "expected_error 不是支持的守卫错误码";
            }
        }
        boolean passed = expected != null && expected.equals(actual);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("expectedError", expected);
        detail.put("reason", reason);
        detail.put("note", evalCase.question());
        return new EvalCaseResult(evalCase.id(), evalCase.question(), "GUARD",
                expected, actual, passed, passed, true, true, true,
                0, 0, 0, false, passed ? null : "EXPECTED_ERROR", null,
                duration(start), detail);
    }

    private EvalCaseResult sessionCase(EvalDatasetService.AgentCase evalCase, Prepared prepared,
                                       long start) {
        UUID sessionId = sessions.ensure(null);
        try {
            String setup = evalCase.setupQuestion();
            Map<String, String> scope = scopeOf(setup);
            String setupMetric = MetricRouter.route(setup, prepared.props().getMetric());
            sessions.record(sessionId, setup, setup, "ATTRIBUTION", UUID.randomUUID(), setupMetric, scope);

            SessionPreparer.Prepared preparedFollowUp = sessionPreparer.prepare(sessionId.toString(),
                    evalCase.question());
            boolean expectClarify = "CLARIFY".equals(evalCase.expectedError());
            boolean clarify = preparedFollowUp.clarify();
            boolean resolvedMatch = !clarify
                    && containsAll(preparedFollowUp.resolved(), evalCase.expectedResolvedContains());
            boolean passed = expectClarify ? clarify : resolvedMatch;
            String actual = clarify ? "CLARIFY" : preparedFollowUp.resolved();
            String expected = expectClarify ? "CLARIFY"
                    : "contains " + String.join(", ", evalCase.expectedResolvedContains());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("setup", setup);
            detail.put("resolved", preparedFollowUp.resolved());
            detail.put("note", preparedFollowUp.note());
            detail.put("sessionId", sessionId.toString());
            return new EvalCaseResult(evalCase.id(), evalCase.question(), "SESSION",
                    expected, actual, passed, passed, true, true, true,
                    0, 0, 0, false,
                    passed ? null : expectClarify ? "CLARIFY" : "RESOLVED",
                    null, duration(start), detail);
        } finally {
            jdbc.update("delete from session_state where id = ?", sessionId);
        }
    }

    private Map<String, String> scopeOf(String question) {
        try {
            return scopeParser.parse(question).filters().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> entry.getKey().name(), Map.Entry::getValue));
        } catch (AmbiguousScopeException e) {
            return Map.of();
        }
    }

    private EvalCaseResult exceptionResult(EvalDatasetService.AgentCase evalCase,
                                           RuntimeException error, long elapsed) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exception", error.getClass().getSimpleName());
        detail.put("message", error.getMessage());
        return new EvalCaseResult(evalCase.id(), evalCase.question(), evalCase.kind(),
                expectedOf(evalCase), "EXCEPTION: " + error.getMessage(), false,
                false, false, false, false, 0, 0, 0, false, "EXCEPTION",
                null, (int) Math.min(Integer.MAX_VALUE, elapsed), detail);
    }

    private static String expectedOf(EvalDatasetService.AgentCase evalCase) {
        return switch (evalCase.kind()) {
            case "GUARD" -> evalCase.expectedError();
            case "SESSION" -> evalCase.expectedError() != null
                    ? evalCase.expectedError()
                    : "contains " + String.join(", ", evalCase.expectedResolvedContains());
            case "LOOKUP" -> "SUCCEEDED";
            default -> evalCase.expectedOutcome();
        };
    }

    private boolean answerMatches(List<String> expected, String answer) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (answer == null) {
            return false;
        }
        return expected.stream().anyMatch(answer::contains);
    }

    private static boolean containsAll(String text, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return true;
        }
        return text != null && tokens.stream().allMatch(text::contains);
    }

    private RunMetrics runMetrics(UUID runId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select (select count(*) from agent_step where run_id = r.id) as steps,
                       coalesce(r.prompt_tokens, 0) + coalesce(r.completion_tokens, 0) as tokens
                  from agent_run r where r.id = ?
                """, runId);
        return new RunMetrics(((Number) row.get("steps")).intValue(),
                ((Number) row.get("tokens")).intValue());
    }

    private static Map<String, Object> factStats(Findings findings) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("facts", findings.facts().size());
        stats.put("hypothesis", findings.hypothesis().size());
        stats.put("uncertainty", findings.uncertainty().size());
        stats.put("factsDowngraded", findings.uncertainty().stream()
                .filter(text -> text.contains("已从 facts 降级")).count());
        stats.put("causalMoved", findings.hypothesis().stream()
                .filter(text -> text.contains("因果表述")).count());
        return stats;
    }

    private static int duration(long startMillis) {
        return (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - startMillis);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...(truncated)";
    }

    private record RunMetrics(int steps, int tokens) {
    }

    private record AttributionServiceHolder(
            com.insight.agent.attribution.AttributionService attribution, AgentLoop loop) {
    }
}
