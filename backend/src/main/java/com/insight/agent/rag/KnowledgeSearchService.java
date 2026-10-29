package com.insight.agent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 知识检索：向量 + 关键词，RRF 融合。
 *
 * <p>关键词路用 pg_trgm 而不是 tsvector：Postgres 没有中文分词器，
 * to_tsvector 会把"退款率怎么算"整串当一个 token，召回接近 0。
 * 装 zhparser 要自定义镜像 + 维护词典，对 60 篇文档的知识库不划算。
 */
@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    /** RRF 常数。取 60 是论文里的经验值：越大越平滑，越小越偏向头部结果。 */
    private static final int RRF_K = 60;

    public enum Mode { VECTOR, KEYWORD, HYBRID }

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public KnowledgeSearchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    public List<SearchHit> search(String query, int topK, Mode mode) {
        List<SearchHit> vectorHits = mode == Mode.KEYWORD ? List.of() : vectorSearch(query, topK * 4);
        List<SearchHit> keywordHits = mode == Mode.VECTOR ? List.of() : keywordSearch(query, topK * 4);

        List<SearchHit> fused = switch (mode) {
            case VECTOR -> vectorHits;
            case KEYWORD -> keywordHits;
            case HYBRID -> rrfFuse(vectorHits, keywordHits);
        };
        return fused.stream().limit(topK).toList();
    }

    private List<SearchHit> vectorSearch(String query, int limit) {
        float[] embedding = embeddingModel.embed(query);
        String literal = toVectorLiteral(embedding);
        return jdbc.query("""
                select id, doc_type, title, content, 1 - (embedding <=> ?::vector) as score
                from knowledge_doc
                where embedding is not null
                order by embedding <=> ?::vector
                limit ?
                """, (rs, i) -> new SearchHit(
                        rs.getLong("id"), rs.getString("doc_type"),
                        rs.getString("title"), rs.getString("content"),
                        rs.getDouble("score")),
                literal, literal, limit);
    }

    private List<SearchHit> keywordSearch(String query, int limit) {
        return jdbc.query("""
                select id, doc_type, title, content,
                       greatest(similarity(title, ?),
                                word_similarity(?, content || ' ' || array_to_string(synonyms, ' '))) as score
                from knowledge_doc
                where greatest(similarity(title, ?),
                               word_similarity(?, content || ' ' || array_to_string(synonyms, ' '))) > 0.05
                order by score desc
                limit ?
                """, (rs, i) -> new SearchHit(
                        rs.getLong("id"), rs.getString("doc_type"),
                        rs.getString("title"), rs.getString("content"),
                        rs.getDouble("score")),
                query, query, query, query, limit);
    }

    /** Reciprocal Rank Fusion：只看排名，不看分数。两路分数量纲不同，直接加权会偏。 */
    private List<SearchHit> rrfFuse(List<SearchHit> a, List<SearchHit> b) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, SearchHit> byId = new LinkedHashMap<>();

        for (List<SearchHit> list : List.of(a, b)) {
            for (int rank = 0; rank < list.size(); rank++) {
                SearchHit hit = list.get(rank);
                scores.merge(hit.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
                byId.putIfAbsent(hit.id(), hit);
            }
        }

        List<SearchHit> merged = new ArrayList<>();
        scores.forEach((id, score) -> {
            SearchHit hit = byId.get(id);
            merged.add(new SearchHit(hit.id(), hit.docType(), hit.title(), hit.content(), score));
        });
        merged.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return merged;
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

    public int embeddedCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from knowledge_doc where embedding is not null", Integer.class);
        return count == null ? 0 : count;
    }

    public int totalCount() {
        Integer count = jdbc.queryForObject("select count(*) from knowledge_doc", Integer.class);
        return count == null ? 0 : count;
    }
}
