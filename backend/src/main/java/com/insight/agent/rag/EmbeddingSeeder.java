package com.insight.agent.rag;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 补齐缺失的向量。
 *
 * <p>按批处理而不是逐条：embedding 接口单次支持批量，逐条调 60 次既慢又容易触发限流。
 * 已经算过的不重算——文档内容变了才需要重算，这个判断交给上层。
 */
@Component
@ConditionalOnProperty(name = "agent.embedding.seed", havingValue = "true")
public class EmbeddingSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSeeder.class);
    private static final int BATCH = 10;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public EmbeddingSeeder(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                select id, title, content, synonyms from knowledge_doc
                where embedding is null order by id
                """);
        if (pending.isEmpty()) {
            log.info("所有知识文档已有向量，跳过");
            return;
        }
        log.info("需要补向量: {} 篇", pending.size());

        for (int from = 0; from < pending.size(); from += BATCH) {
            List<Map<String, Object>> batch = pending.subList(from, Math.min(from + BATCH, pending.size()));
            List<String> texts = batch.stream().map(EmbeddingSeeder::embeddingText).toList();

            List<float[]> vectors = embeddingModel.embed(texts);
            for (int i = 0; i < batch.size(); i++) {
                jdbc.update("update knowledge_doc set embedding = ?::vector where id = ?",
                        toVectorLiteral(vectors.get(i)), batch.get(i).get("id"));
            }
            log.info("已补 {}/{}", Math.min(from + BATCH, pending.size()), pending.size());
        }
    }

    /** 标题 + 别名 + 正文一起嵌入：别名能显著提升口语化查询的召回。 */
    private static String embeddingText(Map<String, Object> row) {
        Object synonyms = row.get("synonyms");
        String alias = "";
        if (synonyms instanceof String[] arr) {
            alias = Arrays.stream(arr).collect(Collectors.joining("、"));
        }
        return row.get("title") + " " + alias + " " + row.get("content");
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
