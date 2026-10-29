package com.insight.agent.eval;

import com.insight.agent.agent.AgentLoop;
import com.insight.agent.agent.AgentProperties;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/** 用覆盖参数构造一次性的查数循环；与归因变体同样，只活在评测批次里。 */
@Component
public class AgentLoopFactory {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final RunRecorder recorder;
    private final AgentProperties props;
    private final EvalPromptLibrary prompts;

    public AgentLoopFactory(LlmClient llm, ToolRegistry tools, RunRecorder recorder,
                            AgentProperties props, EvalPromptLibrary prompts) {
        this.llm = llm;
        this.tools = tools;
        this.recorder = recorder;
        this.props = props;
        this.prompts = prompts;
    }

    public AgentLoop create(Integer maxSteps, Integer tokenBudget) {
        AgentProperties variant = props.copy();
        if (maxSteps != null) {
            variant.setMaxSteps(maxSteps);
        }
        if (tokenBudget != null) {
            variant.setTokenBudget(tokenBudget);
        }
        return new AgentLoop(llm, tools, recorder, variant, prompts.system().text());
    }
}
