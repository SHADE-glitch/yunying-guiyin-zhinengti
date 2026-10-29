package com.insight.agent.attribution;

import com.insight.agent.attribution.AttributionPorts.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从问题文本里认出根范围："为什么华东 GMV 跌了" → REGION=华东。
 *
 * <p>为什么根范围要从问题里来：埋的异常在华东是 -8.3%，平台整体只有 -2.6%，
 * 低于 3% 的显著性门槛。如果根永远是全平台，这个问题会被回答成"没有显著变化"。
 * 归因的起点必须是用户问的那个范围。
 *
 * <p>为什么用词表匹配而不是让模型抽取：词表是有限且已知的（4 个大区 + 16 个城市 + 品类 + 门店），
 * 匹配结果是确定的、可测的；模型抽取会给出词表外的值，还得再校验一遍。等要做开放式表达
 * （"上个月华南那几个新店"）再上模型，那时模型是补足，不是替代。
 *
 * <p>歧义不猜：同一维度认出两个值（"华东和华南"）直接报错，让人把问题问清楚。
 */
@Component
public class AttributionScopeParser {

    /** 指代性说法必须重问：解析不出来的"它"会被静默当成全平台，答的是另一个问题。 */
    private static final List<String> DEMONSTRATIVES = List.of(
            "那家店", "这家店", "那个店", "这个店", "那家商户", "这家商户",
            "那家门店", "这家门店", "那个品类", "这个品类",
            "那个区域", "这个区域", "那个城市", "这个城市");

    private final JdbcTemplate jdbc;
    private volatile List<Named> vocabulary;

    public AttributionScopeParser(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Scope parse(String question) {
        if (question == null || question.isBlank()) {
            return Scope.root();
        }
        for (String phrase : DEMONSTRATIVES) {
            if (question.contains(phrase)) {
                throw new AmbiguousScopeException("问题里的「" + phrase
                        + "」指代不明确，请说出具体的门店 / 品类 / 城市名称");
            }
        }
        Map<Dimension, String> matched = new LinkedHashMap<>();
        for (Named named : vocabulary()) {
            if (!question.contains(named.name())) {
                continue;
            }
            String existing = matched.get(named.dimension());
            if (existing != null) {
                // 长名先匹配，短名是它的子串就说明是同一个实体（"上海" vs "上海市"）
                if (existing.contains(named.name())) {
                    continue;
                }
                throw new AmbiguousScopeException(named.dimension().label() + "同时出现了「"
                        + existing + "」和「" + named.name() + "」，无法确定归因范围");
            }
            matched.put(named.dimension(), named.name());
        }
        return new Scope(Map.copyOf(matched));
    }

    /** 长名优先，避免"上海"抢在"上海市"前面命中。 */
    private List<Named> vocabulary() {
        List<Named> cached = vocabulary;
        if (cached != null) {
            return cached;
        }
        List<Named> loaded = new ArrayList<>();
        jdbc.queryForList("select name from region", String.class)
                .forEach(name -> loaded.add(new Named(Dimension.REGION, name)));
        jdbc.queryForList("select name from category", String.class)
                .forEach(name -> loaded.add(new Named(Dimension.CATEGORY, name)));
        jdbc.queryForList("select name from shop", String.class)
                .forEach(name -> loaded.add(new Named(Dimension.SHOP, name)));
        loaded.sort(Comparator.comparingInt((Named n) -> n.name().length()).reversed()
                .thenComparing(Named::name));
        vocabulary = List.copyOf(loaded);
        return vocabulary;
    }

    private record Named(Dimension dimension, String name) {
    }

    /** 同一维度认出多个值。属于"问题本身有歧义"，不是查询失败。 */
    public static class AmbiguousScopeException extends RuntimeException {

        public AmbiguousScopeException(String message) {
            super(message);
        }
    }
}
