package com.insight.agent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 循环上限。防止模型反复调工具烧钱。 */
    private int maxSteps = 6;

    /** 单次提问允许消耗的 token 上限；0 表示不限制。超了就不再发下一次请求。 */
    private int tokenBudget = 30000;

    private Reflection reflection = new Reflection();

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public Reflection getReflection() {
        return reflection;
    }

    public void setReflection(Reflection reflection) {
        this.reflection = reflection;
    }

    /** 评测变体用：复制一份，不碰正在服务的全局配置。 */
    public AgentProperties copy() {
        AgentProperties copy = new AgentProperties();
        copy.maxSteps = maxSteps;
        copy.tokenBudget = tokenBudget;
        copy.reflection = new Reflection();
        copy.reflection.failureThreshold = reflection.failureThreshold;
        copy.reflection.maxRounds = reflection.maxRounds;
        return copy;
    }

    public static class Reflection {

        /** 连续失败几次才触发自省。单次失败交给模型自己改。 */
        private int failureThreshold = 2;

        /** 一次提问里最多自省几轮。 */
        private int maxRounds = 2;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }
    }
}
