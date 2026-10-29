package com.insight.agent.attribution.sql;

/** 查询失败。分成三类是因为调用方要给出不同的失败原因，模型/日志都要能看懂。 */
public class SqlExecutionException extends RuntimeException {

    public enum Kind {
        /** 被 SqlGuard 拒绝，没有打到数据库 */
        REJECTED,
        /** 执行超时 */
        TIMEOUT,
        /** 其他执行错误（连接、语法、权限等） */
        FAILED
    }

    private final Kind kind;

    public SqlExecutionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SqlExecutionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
