package com.insight.agent.tool;

import java.util.Map;

/** 一个可被 Agent 调用的工具。实现类自己声明 schema，注册表只负责转发。 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema 字符串，直接发给模型。 */
    String inputSchema();

    /** 返回结构化结果。不要抛异常表达业务失败，用 ToolOutcome 说明原因。 */
    ToolOutcome execute(Map<String, Object> args) throws Exception;
}
