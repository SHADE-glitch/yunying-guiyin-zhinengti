package com.insight.agent.tool;

/**
 * 工具调用的结构化结果。
 *
 * <p>不用字符串前缀判断成败：REJECTED（被护栏拦下）和 EMPTY（查不到）需要不同的补救动作，
 * 混在一起模型只能瞎猜。
 */
public record ToolOutcome(Status status, String content) {

    public enum Status {
        /** 正常返回数据 */
        OK,
        /** 语法合法但结果为空 */
        EMPTY,
        /** 被护栏拒绝，模型改写法就能过 */
        REJECTED,
        /** 执行出错 */
        ERROR
    }

    public static ToolOutcome ok(String content) {
        return new ToolOutcome(Status.OK, content);
    }

    public static ToolOutcome empty(String content) {
        return new ToolOutcome(Status.EMPTY, content);
    }

    public static ToolOutcome rejected(String reason) {
        return new ToolOutcome(Status.REJECTED, reason);
    }

    public static ToolOutcome error(String message) {
        return new ToolOutcome(Status.ERROR, message);
    }

    public boolean failed() {
        return status == Status.REJECTED || status == Status.ERROR;
    }

    /** 没拿到有效数据：失败和空结果都算。 */
    public boolean unusable() {
        return status != Status.OK;
    }
}
