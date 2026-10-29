package com.insight.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.agent.ReflectionTracker;
import com.insight.agent.tool.ToolOutcome;
import org.junit.jupiter.api.Test;

class ReflectionTrackerTest {

    @Test
    void singleFailureDoesNotTriggerReflection() {
        ReflectionTracker tracker = new ReflectionTracker(2, 2);
        tracker.record("run_sql", "{}", ToolOutcome.rejected("只允许 SELECT"));

        assertThat(tracker.shouldReflect()).isFalse();
        assertThat(tracker.consumeReflectionMessage()).isNull();
    }

    @Test
    void consecutiveFailuresTriggerReflection() {
        ReflectionTracker tracker = new ReflectionTracker(2, 2);
        tracker.record("run_sql", "{}", ToolOutcome.rejected("只允许 SELECT"));
        tracker.record("run_sql", "{}", ToolOutcome.empty("没有行"));

        String message = tracker.consumeReflectionMessage();
        assertThat(message).contains("[自省]").contains("run_sql");
        // 提示里点名的工具必须真实存在：曾经写成 search_metrics，模型照着调只会多一次未知工具错误
        assertThat(message).contains("search_knowledge").doesNotContain("search_metrics");
    }

    @Test
    void successResetsTheCounter() {
        ReflectionTracker tracker = new ReflectionTracker(2, 2);
        tracker.record("run_sql", "{}", ToolOutcome.rejected("只允许 SELECT"));
        tracker.record("run_sql", "{}", ToolOutcome.ok("[{\"a\":1}]"));
        tracker.record("run_sql", "{}", ToolOutcome.empty("没有行"));

        assertThat(tracker.shouldReflect()).isFalse();
    }

    @Test
    void reflectionRoundsAreCapped() {
        ReflectionTracker tracker = new ReflectionTracker(1, 2);

        tracker.record("run_sql", "{}", ToolOutcome.error("boom"));
        assertThat(tracker.consumeReflectionMessage()).isNotNull();

        tracker.record("run_sql", "{}", ToolOutcome.error("boom"));
        assertThat(tracker.consumeReflectionMessage()).isNotNull();

        tracker.record("run_sql", "{}", ToolOutcome.error("boom"));
        assertThat(tracker.shouldReflect()).isFalse();
        assertThat(tracker.consumeReflectionMessage()).isNull();
        assertThat(tracker.rounds()).isEqualTo(2);
    }

    @Test
    void repeatedIdenticalCallIsCalledOut() {
        ReflectionTracker tracker = new ReflectionTracker(2, 2);
        String args = "{\"sql\":\"select * from nope\"}";
        tracker.record("run_sql", args, ToolOutcome.error("relation does not exist"));
        tracker.record("run_sql", args, ToolOutcome.error("relation does not exist"));

        assertThat(tracker.consumeReflectionMessage()).contains("相同的参数");
    }
}
