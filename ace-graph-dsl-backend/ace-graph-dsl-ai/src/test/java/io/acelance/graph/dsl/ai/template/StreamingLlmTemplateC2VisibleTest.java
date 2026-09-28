package io.acelance.graph.dsl.ai.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C2：工具轮中间文本不进 visible，仅终答轮进入。
 */
class StreamingLlmTemplateC2VisibleTest {

    @Test
    void intermediateRound_withToolCalls_notAppendedToVisible() {
        StringBuilder visible = new StringBuilder();
        boolean committed = StreamingLlmTemplate.appendRoundToVisibleIfFinal(
                visible, "步骤1: 读取规则\n步骤2: 校验附件", true);
        assertFalse(committed);
        assertEquals("", visible.toString());
    }

    @Test
    void finalRound_withoutToolCalls_appendedToVisible() {
        StringBuilder visible = new StringBuilder();
        boolean committed = StreamingLlmTemplate.appendRoundToVisibleIfFinal(
                visible, "点击下载：https://example.com/a.xlsx", false);
        assertTrue(committed);
        assertEquals("点击下载：https://example.com/a.xlsx", visible.toString());
    }

    @Test
    void emptyRound_notCommitted() {
        StringBuilder visible = new StringBuilder("prev");
        assertFalse(StreamingLlmTemplate.appendRoundToVisibleIfFinal(visible, "", false));
        assertEquals("prev", visible.toString());
    }

    @Test
    void multiRound_onlyFinalInVisible() {
        StringBuilder visible = new StringBuilder();
        StreamingLlmTemplate.appendRoundToVisibleIfFinal(visible, "Let me read the skill first.", true);
        StreamingLlmTemplate.appendRoundToVisibleIfFinal(visible, "步骤3: 确认工作表", true);
        StreamingLlmTemplate.appendRoundToVisibleIfFinal(visible, "发现未映射部门，请修改后重试。", false);
        assertEquals("发现未映射部门，请修改后重试。", visible.toString());
    }
}
