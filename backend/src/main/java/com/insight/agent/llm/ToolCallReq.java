package com.insight.agent.llm;

/** 模型返回的一次工具调用请求。 */
public record ToolCallReq(String id, String name, String arguments) {
}
