package com.insight.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class EvalPromptLibraryTest {

    private final EvalPromptLibrary library;

    EvalPromptLibraryTest() throws Exception {
        this.library = new EvalPromptLibrary(
                new ClassPathResource("prompts/attribution-proposer.txt"),
                new ClassPathResource("prompts/attribution-reporter.txt"),
                new ClassPathResource("prompts/system.txt"));
    }

    @Test
    @DisplayName("默认 prompt 与 variants 目录下的实验版本都会被加载")
    void loadsDefaultAndVariants() {
        assertThat(library.proposer("default").name()).isEqualTo("default");
        assertThat(library.reporter("default").name()).isEqualTo("default");
        assertThat(library.reporter("concise").text()).contains("最多 4 条");
        assertThat(library.list()).anyMatch(p -> p.kind().equals("reporter")
                && p.name().equals("concise"));
    }

    @Test
    @DisplayName("指纹跟着内容走：不同版本不可能有同一个 sha256")
    void hashesIdentifyVariant() {
        assertThat(library.reporter("concise").sha256())
                .isNotEqualTo(library.reporter("default").sha256());

        assertThat(library.hashes("default", "concise"))
                .containsEntry("reporterVariant", "concise")
                .containsKeys("proposerSha", "reporterSha", "systemSha");
    }

    @Test
    @DisplayName("未知变体：启动批次前就报错，而不是悄悄用默认 prompt")
    void unknownVariantFailsLoudly() {
        assertThatThrownBy(() -> library.reporter("v999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的 reporter prompt 变体");
    }
}
