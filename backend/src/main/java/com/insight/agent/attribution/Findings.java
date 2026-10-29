package com.insight.agent.attribution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归因结论的三段式表述。
 *
 * <p>分开是刻意的：`facts` 只能是证据里有的数字，`hypothesis` 是推测，
 * `uncertainty` 是"我不知道"。混在一段话里，读者没法区分哪句是查出来的、哪句是猜的。
 */
public record Findings(List<String> facts, List<String> hypothesis, List<String> uncertainty) {

    public Map<String, Object> asMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facts", facts);
        data.put("hypothesis", hypothesis);
        data.put("uncertainty", uncertainty);
        return data;
    }
}
