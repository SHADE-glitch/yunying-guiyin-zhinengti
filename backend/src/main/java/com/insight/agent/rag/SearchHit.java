package com.insight.agent.rag;

public record SearchHit(long id, String docType, String title, String content, double score) {
}
