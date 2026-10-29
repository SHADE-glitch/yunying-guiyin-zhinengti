package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insight.agent.attribution.AttributionScopeParser.AmbiguousScopeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指代性范围必须重问，不能静默退化成全平台。
 *
 * <p>用 null JdbcTemplate：指代检查发生在词表加载之前，这条路径不需要数据库。
 */
class ScopeParserAnaphoraTest {

    private final AttributionScopeParser parser = new AttributionScopeParser(null);

    @Test
    @DisplayName("「那家店」类说法：CLARIFY，而不是当成全平台")
    void demonstrativeScopeIsClarifiedNotGuessed() {
        assertThatThrownBy(() -> parser.parse("那家店为什么跌了"))
                .isInstanceOf(AmbiguousScopeException.class)
                .hasMessageContaining("那家店");
        assertThatThrownBy(() -> parser.parse("这个品类呢"))
                .isInstanceOf(AmbiguousScopeException.class)
                .hasMessageContaining("这个品类");
        assertThatThrownBy(() -> parser.parse("这个城市最近怎么样"))
                .isInstanceOf(AmbiguousScopeException.class)
                .hasMessageContaining("这个城市");
    }

    @Test
    @DisplayName("空问题：根范围，不碰数据库")
    void blankQuestionIsRoot() {
        assertThat(parser.parse("  ").filters()).isEmpty();
    }
}
