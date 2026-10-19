package io.acelance.graph.dsl.ai.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.acelance.graph.dsl.ai.skill.ForceSkillActivator.ActivatedSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 内部 vLLM 只接受一条 system：节点提示词与强制技能必须拼在同一条里。
 */
class StreamingLlmTemplateSystemMergeTest {

    @Test
    void blankInputs_returnsNull() {
        assertNull(StreamingLlmTemplate.composeSingleSystemText("  ", List.of()));
        assertNull(StreamingLlmTemplate.composeSingleSystemText(null, null));
    }

    @Test
    void promptOnly_keepsSingleBlock() {
        assertEquals("你是核对助手", StreamingLlmTemplate.composeSingleSystemText("你是核对助手", List.of()));
    }

    @Test
    void promptAndForcedSkills_joinedIntoOne() {
        String merged = StreamingLlmTemplate.composeSingleSystemText(
                "你是核对助手",
                List.of(
                        new ActivatedSkill("paddleocr-text-recognition", "先 OCR"),
                        new ActivatedSkill("other-skill", "再核对")));
        assertTrue(merged.startsWith("你是核对助手\n\n[forceSkills activated] skill=paddleocr-text-recognition"));
        assertTrue(merged.contains("先 OCR"));
        assertTrue(merged.contains("[forceSkills activated] skill=other-skill\n\n再核对"));
        assertEquals(1, merged.split("你是核对助手", -1).length - 1);
    }
}
