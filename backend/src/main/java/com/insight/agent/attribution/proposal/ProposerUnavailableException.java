package com.insight.agent.attribution.proposal;

/**
 * 提议器**不可用**（模型调用失败、超时、限流），不是"提议内容不合法"。
 *
 * <p>两者要分开：不可用可以回退到规则版，内容不合法必须交给 ProposalGuard 计数——
 * 后者是 prompt 质量问题，回退会把它藏起来，永远发现不了。
 */
public class ProposerUnavailableException extends RuntimeException {

    public ProposerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
