package com.insight.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.llm.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    public ToolRegistry(List<AgentTool> discovered, ObjectMapper mapper) {
        this.mapper = mapper;
        discovered.forEach(t -> {
            // 启动期校验：schema 不是合法 JSON 的话，请求会在框架内部炸掉，
            // 报错信息指向 Spring AI 而不是我们自己的工具，排查成本很高。
            try {
                mapper.readTree(t.inputSchema());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "工具 " + t.name() + " 的 inputSchema 不是合法 JSON: " + e.getMessage(), e);
            }
            tools.put(t.name(), t);
        });
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    public ToolOutcome invoke(String name, String jsonArgs) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return ToolOutcome.error("未知工具: " + name);
        }
        Map<String, Object> args;
        try {
            args = jsonArgs == null || jsonArgs.isBlank()
                    ? Map.of()
                    : mapper.readValue(jsonArgs, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return ToolOutcome.error("参数不是合法 JSON: " + e.getMessage());
        }
        try {
            return tool.execute(args);
        } catch (Exception e) {
            return ToolOutcome.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public int size() {
        return tools.size();
    }
}
