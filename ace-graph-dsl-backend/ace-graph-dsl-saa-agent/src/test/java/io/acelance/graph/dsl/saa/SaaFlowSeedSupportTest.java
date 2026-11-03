package io.acelance.graph.dsl.saa;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAA seed：从 input 补齐 messages，供 ROUTING 路由器看见用户话。
 */
class SaaFlowSeedSupportTest {

    private static final Logger log = LoggerFactory.getLogger(SaaFlowSeedSupportTest.class);

    @Test
    void ensureMessagesFromInput_fillsUserMessageWhenMissing() {
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("user_query", "fee_type=discount_auto 流程费用23129.98");
        seed.put("input", "fee_type=discount_auto 流程费用23129.98");

        boolean filled = SaaFlowSeedSupport.ensureMessagesFromInput(seed);
        assertTrue(filled);
        Object messages = seed.get("messages");
        assertInstanceOf(List.class, messages);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) messages;
        assertEquals(1, list.size());
        assertInstanceOf(UserMessage.class, list.get(0));
        assertEquals("fee_type=discount_auto 流程费用23129.98", ((UserMessage) list.get(0)).getText());
        log.info("ensureMessagesFromInput 补齐通过: chars={}",
                ((UserMessage) list.get(0)).getText().length());
    }

    @Test
    void ensureMessagesFromInput_skipsWhenMessagesAlreadyPresent() {
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("input", "should-not-overwrite");
        List<Object> existing = new ArrayList<>();
        existing.add(new UserMessage("already-there"));
        seed.put("messages", existing);

        boolean filled = SaaFlowSeedSupport.ensureMessagesFromInput(seed);
        assertFalse(filled);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) seed.get("messages");
        assertEquals(1, list.size());
        assertEquals("already-there", ((UserMessage) list.get(0)).getText());
        log.info("ensureMessagesFromInput 跳过已有 messages 通过");
    }

    @Test
    void ensureMessagesFromInput_noopWhenInputBlank() {
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("input", "  ");
        assertFalse(SaaFlowSeedSupport.ensureMessagesFromInput(seed));
        assertFalse(seed.containsKey("messages"));
        log.info("ensureMessagesFromInput input 空白 noop 通过");
    }
}
