package com.insight.agent.tool;

import com.insight.agent.rag.KnowledgeSearchService;
import com.insight.agent.rag.SearchHit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 知识检索：指标口径、平台规则、常见问题。
 *
 * <p>W1 这里是 ILIKE 关键词匹配，W2 换成向量 + pg_trgm 双路召回再做 RRF 融合。
 * 融合的意义：向量擅长语义相近但字面不同的问法，关键词擅长精确命中指标名和券码。
 */
@Component
public class SearchKnowledgeTool implements AgentTool {

    private final KnowledgeSearchService searchService;

    public SearchKnowledgeTool(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索知识库：指标口径（GMV、客单价、退款率等怎么算）、平台规则（退款、核销、券叠加）、"
                + "常见问题（数据为什么对不上、为什么某城市跌了）。"
                + "问指标、规则、异常原因之前先调用它，不要臆测口径。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string", "description": "用户原话或关键词，例如 销售额怎么算、券能叠加吗"}
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public ToolOutcome execute(Map<String, Object> args) {
        String query = String.valueOf(args.get("query"));
        List<SearchHit> hits = searchService.search(query, 3, KnowledgeSearchService.Mode.HYBRID);

        if (hits.isEmpty()) {
            return ToolOutcome.empty("知识库里没有匹配内容。可以直接用字段名写 SQL，但要在回答里说明口径。");
        }

        StringBuilder sb = new StringBuilder();
        for (SearchHit hit : hits) {
            sb.append('[').append(hit.docType()).append("] ").append(hit.title()).append('\n')
                    .append(hit.content()).append('\n')
                    .append("(相关度 ").append(String.format("%.3f", hit.score())).append(")\n\n");
        }
        return ToolOutcome.ok(sb.toString().strip());
    }
}
