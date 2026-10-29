package com.insight.agent.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * prompt 版本库：默认 prompt + prompts/variants 下的实验版本。
 *
 * <p>评测批次记录实际使用的 prompt 名称与 sha256。只改 prompt 文件不记指纹，
 * 两次评测的差异就说不清是数据、模型还是措辞造成的。
 */
@Component
public class EvalPromptLibrary {

    public record Prompt(String kind, String name, String text, String sha256) {
    }

    public record PromptInfo(String kind, String name, String sha256) {
    }

    private static final String PROPOSER_PREFIX = "attribution-proposer-";
    private static final String REPORTER_PREFIX = "attribution-reporter-";

    private final Map<String, Prompt> proposers = new LinkedHashMap<>();
    private final Map<String, Prompt> reporters = new LinkedHashMap<>();
    private final Prompt system;

    public EvalPromptLibrary(
            @Value("classpath:prompts/attribution-proposer.txt") Resource defaultProposer,
            @Value("classpath:prompts/attribution-reporter.txt") Resource defaultReporter,
            @Value("classpath:prompts/system.txt") Resource systemPrompt) throws IOException {
        putOnce(proposers, prompt("proposer", "default", defaultProposer));
        putOnce(reporters, prompt("reporter", "default", defaultReporter));
        loadVariants(proposers, "proposer", PROPOSER_PREFIX);
        loadVariants(reporters, "reporter", REPORTER_PREFIX);
        this.system = prompt("system", "default", systemPrompt);
    }

    public Prompt proposer(String name) {
        return find(proposers, name, "proposer");
    }

    public Prompt reporter(String name) {
        return find(reporters, name, "reporter");
    }

    public Prompt system() {
        return system;
    }

    public Map<String, String> hashes(String proposerName, String reporterName) {
        Prompt p = proposer(proposerName);
        Prompt r = reporter(reporterName);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("proposerVariant", p.name());
        out.put("proposerSha", p.sha256());
        out.put("reporterVariant", r.name());
        out.put("reporterSha", r.sha256());
        out.put("systemSha", system.sha256());
        return out;
    }

    public List<PromptInfo> list() {
        List<PromptInfo> out = new ArrayList<>();
        proposers.values().forEach(p -> out.add(new PromptInfo(p.kind(), p.name(), p.sha256())));
        reporters.values().forEach(p -> out.add(new PromptInfo(p.kind(), p.name(), p.sha256())));
        return out;
    }

    private void loadVariants(Map<String, Prompt> into, String kind, String prefix) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath*:prompts/variants/" + prefix + "*.txt")) {
            String filename = resource.getFilename();
            if (filename == null || !filename.startsWith(prefix) || !filename.endsWith(".txt")) {
                continue;
            }
            String name = filename.substring(prefix.length(), filename.length() - ".txt".length());
            if (name.isBlank()) {
                continue;
            }
            putOnce(into, prompt(kind, name, resource));
        }
    }

    private static Prompt prompt(String kind, String name, Resource resource) throws IOException {
        String text = resource.getContentAsString(StandardCharsets.UTF_8);
        return new Prompt(kind, name, text, sha256(text));
    }

    private static void putOnce(Map<String, Prompt> into, Prompt prompt) {
        if (into.putIfAbsent(prompt.name(), prompt) != null) {
            throw new IllegalStateException("prompt 变体重名: " + prompt.kind() + " / " + prompt.name());
        }
    }

    private static Prompt find(Map<String, Prompt> prompts, String name, String kind) {
        String key = name == null || name.isBlank() ? "default" : name.strip();
        Prompt prompt = prompts.get(key);
        if (prompt == null) {
            throw new IllegalArgumentException("未知的 " + kind + " prompt 变体: " + key
                    + "，可用: " + prompts.keySet());
        }
        return prompt;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 prompt 指纹失败", e);
        }
    }
}
