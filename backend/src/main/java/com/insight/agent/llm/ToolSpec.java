package com.insight.agent.llm;

/** 发给模型的工具定义。inputSchema 是 JSON Schema 字符串。 */
public record ToolSpec(String name, String description, String inputSchema) {
}
