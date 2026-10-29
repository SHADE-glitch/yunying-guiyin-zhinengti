package com.insight.agent.agent;

/** 推给 SSE 的一条事件。type 决定前端怎么消费。 */
public record AgentEvent(String type, Object data) {

    public static AgentEvent of(String type, Object data) {
        return new AgentEvent(type, data);
    }
}
