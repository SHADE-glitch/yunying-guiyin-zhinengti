package com.insight.agent.attribution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 一次归因查询的证据。
 *
 * <p>归因结论必须能回溯到"哪条 SQL、返回了几行"。这里只存指纹不存原文：
 * 原文在 {@code agent_step.input} 里，两处都存会有不一致的风险。
 *
 * <p>{@code stepId} 为空表示证据还没绑定到具体步骤（查询层拿不到 run 上下文时）。
 * 绑定时必须真的写进 {@code agent_step}，不能只填一个数字——外键和
 * {@code EvidenceBinding} 会分别校验"步骤存在"和"指纹一致"。
 *
 * <p>指纹计算的是**提交给执行器的那条 SQL 原文**，不含护栏补的 {@code limit}。
 * 记录步骤时也要用同一个字符串，否则校验会失败。
 */
public record Evidence(String sql, String queryHash, int rowCount, Long stepId) {

    public static Evidence of(String sql, int rowCount) {
        return new Evidence(sql, hash(sql), rowCount, null);
    }

    public Evidence boundTo(long stepId) {
        return new Evidence(sql, queryHash, rowCount, stepId);
    }

    public static String hash(String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sql.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
