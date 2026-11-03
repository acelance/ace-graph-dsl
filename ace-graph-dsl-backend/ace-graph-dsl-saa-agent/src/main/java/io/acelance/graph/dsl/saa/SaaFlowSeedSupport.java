package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SAA Flow {@code invoke(seed)} 入参组装辅助：在业务 inputKeys 之外，把父图
 * OverAllState 中的框架保留键（及少量平台上下文键）透传到子 Flow，避免子
 * GenericAgent 丢 {@code agentCode}/{@code runId}/{@code conversationId} 等。
 *
 * <p>实证（lf4qw）：入口已写 agentCode=ztc-service-agent-v2，但 seed 仅含
 * user_query → 子节点 intention_subagent ModelMount 报「缺少 agentCode」。</p>
 */
final class SaaFlowSeedSupport {

    private static final Logger log = LoggerFactory.getLogger(SaaFlowSeedSupport.class);

    /**
     * 非 ace 前缀、但子 Agent 常需要的平台键（多模态 / 附加参数）。
     * 与 {@code AceGraphPlatformRequestMapper} 写入的图 state 键对齐。
     */
    private static final Set<String> PLATFORM_CONTEXT_KEYS = Set.of(
            "media_refs",
            "extra_params",
            "user_account",
            "networking",
            "is_default_deepagent"
    );

    private SaaFlowSeedSupport() {
    }

    /**
     * 从父 state 复制保留键进 seed（不覆盖已有同名键）。
     *
     * @return 实际新写入的键名（仅日志）
     */
    static List<String> copyParentContext(OverAllState parent, Map<String, Object> seed) {
        List<String> copied = new ArrayList<>();
        if (parent == null || seed == null) {
            return copied;
        }
        Map<String, Object> data;
        try {
            data = parent.data();
        } catch (RuntimeException ex) {
            log.warn("SAA seed 读取父 state.data 失败: {}", ex.toString());
            return copied;
        }
        if (data == null || data.isEmpty()) {
            return copied;
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (seed.containsKey(key)) {
                continue;
            }
            if (key.startsWith(ModelOverrideSpec.ACE_RESERVED_PREFIX)
                    || PLATFORM_CONTEXT_KEYS.contains(key)) {
                seed.put(key, value);
                copied.add(key);
            }
        }
        if (!copied.isEmpty()) {
            log.info("SAA seed 已透传父上下文: count={}, keys={}", copied.size(), copied);
        } else {
            log.warn("SAA seed 未从父 state 透传到任何保留/平台键（父 dataKeys={}）；"
                            + "子 Agent 若走 modelConfigKey 可能缺 agentCode",
                    data.keySet());
        }
        return copied;
    }

    /**
     * 保证 seed 含 {@code messages}，供 SAA {@code LlmRoutingAgent}/{@code RoutingNode} 消费。
     *
     * <p>官方 {@code RoutingNode} 只读 {@code state.messages}，再追加固定选路 instruction；
     * 仅有 {@code input}/{@code user_query} 时路由器看不到用户话。对齐
     * {@code Agent#buildMessageInput}：缺 messages 时用 {@code input} 生成一条 {@link UserMessage}。</p>
     *
     * @return true 表示本次新写入了 messages
     */
    static boolean ensureMessagesFromInput(Map<String, Object> seed) {
        if (seed == null) {
            return false;
        }
        Object existing = seed.get("messages");
        if (existing instanceof List<?> list && !list.isEmpty()) {
            log.info("SAA seed 已有 messages，跳过补齐: size={}", list.size());
            return false;
        }
        Object input = seed.get("input");
        if (input == null || !StringUtils.hasText(String.valueOf(input))) {
            log.warn("SAA seed 无法补齐 messages：input 为空, keys={}", seed.keySet());
            return false;
        }
        String text = String.valueOf(input).trim();
        seed.put("messages", List.of(new UserMessage(text)));
        log.info("SAA seed 已从 input 补齐 messages(UserMessage): chars={}, keys={}",
                text.length(), seed.keySet());
        return true;
    }
}
