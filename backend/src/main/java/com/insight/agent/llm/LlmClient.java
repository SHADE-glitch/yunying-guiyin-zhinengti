package com.insight.agent.llm;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 模型调用的唯一出口。
 *
 * <p>刻意不用 Spring AI 的自动工具执行：Agent 循环、重试、护栏、HITL 都要在这里看得见。
 * 框架只负责"把请求发出去、把响应拿回来"。
 */
public interface LlmClient {

    LlmResponse chat(List<Message> messages, List<ToolSpec> tools);
}
