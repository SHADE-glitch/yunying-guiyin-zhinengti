package com.insight.agent.attribution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentEvent;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.attribution.ContributionMath.Split;
import com.insight.agent.attribution.persistence.AttributionNodeWriter;
import com.insight.agent.attribution.proposal.FallbackDimensionProposer;
import com.insight.agent.attribution.proposal.LlmDimensionProposer;
import com.insight.agent.attribution.sql.AttributionProperties;
import com.insight.agent.attribution.sql.QueryWindow;
import com.insight.agent.attribution.sql.SqlExecutor;
import com.insight.agent.attribution.sql.SqlSplitProvider;
import com.insight.agent.llm.BudgetedLlmClient;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.TokenBudget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 归因的编排层：解析根范围 → 建预算 → 跑状态机 → 落库 → 写结论 → 推事件。
 *
 * <p>状态机不认识数据库、模型和 SSE，只认识 {@code SplitProvider}、{@code DimensionProposer}
 * 和 {@code AttributionListener}。所以"控制流对不对"（单测覆盖）和
 * "怎么落库、怎么用模型、怎么推送"（这里）可以分别改。
 */
@Service
public class AttributionService {

    private static final Logger log = LoggerFactory.getLogger(AttributionService.class);

    private final AttributionEngine engine;
    private final SqlExecutor executor;
    private final AttributionProperties props;
    private final RunRecorder recorder;
    private final AttributionNodeWriter writer;
    private final AttributionScopeParser scopeParser;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String proposerPrompt;
    private final String reporterPrompt;

    @Autowired
    public AttributionService(SqlExecutor executor,
                              AttributionProperties props,
                              RunRecorder recorder,
                              AttributionNodeWriter writer,
                              AttributionScopeParser scopeParser,
                              LlmClient llm,
                              ObjectMapper mapper,
                              @Value("classpath:prompts/attribution-proposer.txt") Resource proposerPrompt,
                              @Value("classpath:prompts/attribution-reporter.txt") Resource reporterPrompt)
            throws IOException {
        this(executor, props, recorder, writer, scopeParser, llm, mapper,
                proposerPrompt.getContentAsString(StandardCharsets.UTF_8),
                reporterPrompt.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 评测变体用：prompt 已经由 {@code EvalPromptLibrary} 解析成文本。 */
    public AttributionService(SqlExecutor executor,
                              AttributionProperties props,
                              RunRecorder recorder,
                              AttributionNodeWriter writer,
                              AttributionScopeParser scopeParser,
                              LlmClient llm,
                              ObjectMapper mapper,
                              String proposerPrompt,
                              String reporterPrompt) {
        this.executor = executor;
        this.props = props;
        this.recorder = recorder;
        this.writer = writer;
        this.scopeParser = scopeParser;
        this.llm = llm;
        this.mapper = mapper;
        this.proposerPrompt = proposerPrompt;
        this.reporterPrompt = reporterPrompt;
        this.engine = new AttributionEngine(props.toConfig());
    }

    /**
     * 跑一次归因。事件顺序：baseline → probe* → verdict → answer。
     * 每一步都是刚算完就推，不是攒到最后一次性给。
     */
    public AttributionRun run(String question, Consumer<AgentEvent> sink) {
        UUID runId = UUID.randomUUID();
        // 先建 run 再解析：问不清范围、问错指标也是"用户真的问过"，库里必须留痕。
        // 否则这类请求只有一条 HTTP 日志，事后既统计不出频率，也复盘不了说法。
        recorder.startRun(runId, question, "ATTRIBUTION");

        Scope rootScope;
        // 指标路由先于范围解析：问题点名哪个可加指标就按哪个归因，没点名用配置默认
        String metric = MetricRouter.route(question, props.getMetric());
        try {
            AttributionMetricGuard.check(question, metric);
            rootScope = scopeParser.parse(question);
        } catch (RuntimeException e) {
            recordFailure(runId, e);
            throw e;
        }

        QueryWindow window = QueryWindow.of(props.getAsOf(), props.getWindowDays());
        QueryBudget budget = new QueryBudget(props.getQueryBudget());
        // 模型调用也记账：查询预算管"打几次库"，token 预算管"问几次模型"，两件事分开算
        TokenBudget tokens = new TokenBudget(props.getTokenBudget());
        LlmClient budgeted = new BudgetedLlmClient(llm, tokens);
        SqlSplitProvider provider = new SqlSplitProvider(executor, props, budget, window, rootScope, metric);
        FallbackDimensionProposer proposer = proposer(budgeted, question, rootScope);
        PersistingListener listener = new PersistingListener(runId, rootScope, window, metric, sink);

        AttributionResult result;
        try {
            result = engine.run(proposer, provider, listener);
        } catch (RuntimeException e) {
            recordFailure(runId, e);
            throw e;
        }

        boolean succeeded = succeeded(result.outcome());

        sink.accept(AgentEvent.of("verdict", verdict(result, budget, proposer)));

        Findings findings = new AttributionReporter(budgeted, mapper, reporterPrompt)
                .report(question, rootScope, listener.root(), listener.levels(), result, proposer.degraded());
        sink.accept(AgentEvent.of("answer", findings.asMap()));

        // 结论落 answer：查数路径存的是用户看到的答案，归因对称地存三段式结论，
        // 否则历史详情只能看到证据链、看不到当次结论。放在 reporter 之后：
        // token 汇总要包含报告器这次模型调用。
        recorder.finishRun(runId, succeeded ? "SUCCEEDED" : "FAILED", result.probes(),
                tokens.prompt(), tokens.completion(), findingsJson(findings),
                succeeded ? null : result.message());
        recorder.recordAttributionOutcome(runId, result.outcome().name());

        return new AttributionRun(runId, rootScope, result, findings, proposer.degraded());
    }

    private String findingsJson(Findings findings) {
        try {
            return mapper.writeValueAsString(findings.asMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("三段式结论序列化失败", e);
        }
    }

    /**
     * 记一次失败结局。
     *
     * <p>只有 status=FAILED 的话，事后看不出是归因失败还是查数失败；写结局本身失败
     * 不能盖掉真正的错误——原始异常是主异常，写失败挂到 suppressed。
     */
    private void recordFailure(UUID runId, RuntimeException e) {
        try {
            recorder.recordAttributionOutcome(runId, AttributionOutcome.FAILED.name());
        } catch (RuntimeException writeFailure) {
            e.addSuppressed(writeFailure);
            log.warn("记录归因结局失败: runId={}", runId, writeFailure);
        }
        recorder.finishRun(runId, "FAILED", 0, 0, 0, null, e.getMessage());
    }

    /** 模型优先、规则兜底；关掉模型就只用规则版。降级只影响"选哪个维度"，不影响任何检查。 */
    private FallbackDimensionProposer proposer(LlmClient client, String question, Scope rootScope) {
        LlmDimensionProposer model = props.isModelProposerEnabled()
                ? new LlmDimensionProposer(client, mapper, proposerPrompt, question, rootScope)
                : null;
        return new FallbackDimensionProposer(model, new RuleBasedProposer(rootScope), rootScope);
    }

    /** 没定位到主因也是有效结论（"变化分散"和"没变化"都是答案），只有跑挂和预算耗尽才算失败。 */
    private static boolean succeeded(AttributionOutcome outcome) {
        return switch (outcome) {
            case DOMINANT_FOUND, MULTI_FACTOR, NO_SIGNIFICANT_CHANGE, INSUFFICIENT_EVIDENCE -> true;
            case DATA_INCOMPLETE, INVALID_PROPOSAL_LIMIT, BUDGET_EXHAUSTED, FAILED -> false;
        };
    }

    private static Map<String, Object> verdict(AttributionResult result, QueryBudget budget,
                                               FallbackDimensionProposer proposer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", result.outcome().name());
        data.put("message", result.message());
        data.put("probes", result.probes());
        data.put("budgetUsed", budget.used());
        data.put("budgetLimit", budget.limit());
        data.put("dominantIsAggregate", result.dominantIsAggregate());
        data.put("proposerDegraded", proposer.degraded());
        data.put("path", result.path().stream().map(AttributionService::node).toList());
        return data;
    }

    private static Map<String, Object> node(AttributionNode node) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depth", node.depth());
        data.put("dimension", node.dimension().name());
        data.put("value", node.value());
        data.put("current", node.current());
        data.put("baseline", node.baseline());
        data.put("delta", node.delta());
        data.put("contribution", node.contribution());
        return data;
    }

    private static Scope merge(Scope base, Scope extra) {
        Map<Dimension, String> filters = new LinkedHashMap<>(base.filters());
        filters.putAll(extra.filters());
        return new Scope(Map.copyOf(filters));
    }

    /** 落库 + 推事件。两件事都在这里，是因为它们需要的信息完全一样。 */
    private final class PersistingListener implements AttributionListener {

        private final UUID runId;
        private final Scope rootScope;
        private final QueryWindow window;
        private final String metric;
        private final Consumer<AgentEvent> sink;
        private final Map<String, Long> stepByHash = new HashMap<>();
        private final Map<Integer, Long> nodeByDepth = new HashMap<>();
        private final List<AttributionReporter.Level> levels = new ArrayList<>();
        private Totals root;
        private int stepNo;

        private PersistingListener(UUID runId, Scope rootScope, QueryWindow window, String metric,
                                   Consumer<AgentEvent> sink) {
            this.runId = runId;
            this.rootScope = rootScope;
            this.window = window;
            this.metric = metric;
            this.sink = sink;
        }

        @Override
        public void onBaseline(Totals root) {
            this.root = root;
            Evidence evidence = root.evidence();
            Evidence bound = evidence == null
                    ? null
                    : record("attribution_baseline", Map.of("sql", evidence.sql()), evidence);
            nodeByDepth.put(0, writer.writeRoot(runId, root, bound, rootScope, window, metric));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("current", root.current());
            data.put("baseline", root.baseline());
            data.put("delta", root.delta());
            data.put("scope", scopeMap(rootScope));
            sink.accept(AgentEvent.of("baseline", data));
        }

        @Override
        public void onProbe(int depth, Dimension dimension, Scope scope, ProbeResult result) {
            if (result.evidence() != null) {
                record("attribution_probe", Map.of("dimension", dimension.name(),
                        "scope", scopeMap(scope), "sql", result.evidence().sql()), result.evidence());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("depth", depth);
            data.put("dimension", dimension.name());
            data.put("status", result.status().name());
            data.put("groups", result.items().size());
            if (result.error() != null) {
                data.put("error", result.error());
            }
            sink.accept(AgentEvent.of("probe", data));
        }

        @Override
        public void onSplit(int depth, Dimension dimension, Scope scope, Split split, Scored selected,
                            Evidence evidence) {
            Long parentId = nodeByDepth.get(depth - 1);
            if (parentId == null) {
                return;
            }
            Long stepId = evidence == null ? null : stepByHash.get(evidence.queryHash());
            Evidence bound = (evidence != null && stepId != null) ? evidence.boundTo(stepId) : evidence;
            Scope effective = merge(rootScope, scope);
            long selectedId = writer.writeSplit(runId, parentId, depth, dimension,
                    effective, split, selected, bound, window, metric);
            if (selectedId > 0) {
                nodeByDepth.put(depth, selectedId);
            }
            levels.add(AttributionReporter.Level.of(depth, dimension, effective, split));
        }

        /** 把一条 SQL 记成 agent_step，返回绑定了 step id 的证据。落库和 SSE 用的是同一份。 */
        private Evidence record(String tool, Map<String, Object> input, Evidence evidence) {
            if (evidence == null) {
                return null;
            }
            long id = recorder.recordStepReturningId(runId, ++stepNo, "TOOL_CALL", tool, input,
                    "{\"rowCount\":" + evidence.rowCount() + "}", 0, 0, 0);
            stepByHash.put(evidence.queryHash(), id);
            return evidence.boundTo(id);
        }

        private Totals root() {
            return root;
        }

        private List<AttributionReporter.Level> levels() {
            return List.copyOf(levels);
        }

        private Map<String, String> scopeMap(Scope scope) {
            Map<String, String> raw = new LinkedHashMap<>();
            scope.filters().forEach((dimension, value) -> raw.put(dimension.name(), value));
            return raw;
        }
    }

    /** 一次归因的结果、根范围、三段式结论，以及是否发生了降级。 */
    public record AttributionRun(UUID runId, Scope rootScope, AttributionResult result,
                                 Findings findings, boolean degraded) {

        public List<AttributionNode> path() {
            return result.path();
        }
    }
}
