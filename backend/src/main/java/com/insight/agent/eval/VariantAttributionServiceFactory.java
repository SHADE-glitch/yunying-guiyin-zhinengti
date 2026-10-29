package com.insight.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.attribution.AttributionScopeParser;
import com.insight.agent.attribution.AttributionService;
import com.insight.agent.attribution.persistence.AttributionNodeWriter;
import com.insight.agent.attribution.sql.AttributionProperties;
import com.insight.agent.attribution.sql.SqlExecutor;
import com.insight.agent.llm.LlmClient;
import org.springframework.stereotype.Component;

/**
 * 用一批覆盖参数构造一次性的归因服务。
 *
 * <p>评测不能改全局配置：同一时间用户还在用 /api/attribute。变体只活在批次里，
 * 跑完即弃。
 */
@Component
public class VariantAttributionServiceFactory {

    private final SqlExecutor executor;
    private final RunRecorder recorder;
    private final AttributionNodeWriter writer;
    private final AttributionScopeParser scopeParser;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public VariantAttributionServiceFactory(SqlExecutor executor, RunRecorder recorder,
                                            AttributionNodeWriter writer,
                                            AttributionScopeParser scopeParser,
                                            LlmClient llm, ObjectMapper mapper) {
        this.executor = executor;
        this.recorder = recorder;
        this.writer = writer;
        this.scopeParser = scopeParser;
        this.llm = llm;
        this.mapper = mapper;
    }

    public AttributionService create(AttributionProperties props,
                                     EvalPromptLibrary.Prompt proposer,
                                     EvalPromptLibrary.Prompt reporter) {
        return new AttributionService(executor, props, recorder, writer, scopeParser, llm, mapper,
                proposer.text(), reporter.text());
    }
}
