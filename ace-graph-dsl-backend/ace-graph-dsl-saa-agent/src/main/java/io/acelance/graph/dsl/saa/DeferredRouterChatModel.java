package io.acelance.graph.dsl.saa;

import io.acelance.graph.dsl.llm.AceGraphAgentCodeHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * ROUTING 路由器延迟 {@link ChatModel}：编译期只持有 modelConfigKey，
 * 真正 ModelMount 推迟到首次 {@link #call}/{@link #stream}，
 * 届时从 {@link AceGraphAgentCodeHolder} 读取执行入口 agentCode。
 *
 * <p>避免发布/编译期空 agentCode 失败后错误回落容器默认 ChatModel（错模型）。</p>
 */
final class DeferredRouterChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DeferredRouterChatModel.class);

    private final String graphId;
    private final String nodeId;
    private final String modelConfigKey;
    /** (modelConfigKey, agentCode) → ChatModel */
    private final BiFunction<String, String, ChatModel> mount;

    private volatile ChatModel cached;
    private volatile String cachedAgentCode;

    /**
     * @param graphId        图 ID（日志）
     * @param nodeId         节点 ID（日志）
     * @param modelConfigKey Spec 路由器模型 key
     * @param mount          运行时挂载：(key, agentCode) → ChatModel
     */
    DeferredRouterChatModel(String graphId,
                            String nodeId,
                            String modelConfigKey,
                            BiFunction<String, String, ChatModel> mount) {
        this.graphId = graphId == null ? "" : graphId;
        this.nodeId = nodeId == null ? "" : nodeId;
        this.modelConfigKey = Objects.requireNonNull(modelConfigKey, "modelConfigKey").trim();
        this.mount = Objects.requireNonNull(mount, "mount");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolveDelegate().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resolveDelegate().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return resolveDelegate().getDefaultOptions();
    }

    /**
     * 按 Holder 中的 agentCode 解析并缓存委托 ChatModel。
     */
    private ChatModel resolveDelegate() {
        String agentCode = AceGraphAgentCodeHolder.current();
        if (!StringUtils.hasText(agentCode)) {
            log.error("ROUTING 延迟模型解析失败：运行时 agentCode 为空, graphId={}, nodeId={}, key={}",
                    graphId, nodeId, modelConfigKey);
            throw new IllegalStateException(
                    "ROUTING 路由器缺 agentCode，无法按 modelConfigKey 挂载: key=" + modelConfigKey
                            + ", graphId=" + graphId + ", nodeId=" + nodeId
                            + "（须由执行入口写入 ace.graph.dsl.agentCode，并由 FlowAgentNodeAction 绑定 Holder）");
        }
        String code = agentCode.trim();
        ChatModel hit = cached;
        if (hit != null && code.equals(cachedAgentCode)) {
            return hit;
        }
        synchronized (this) {
            if (cached != null && code.equals(cachedAgentCode)) {
                return cached;
            }
            log.info("ROUTING 运行时解析路由器模型, graphId={}, nodeId={}, key={}, agentCode={}",
                    graphId, nodeId, modelConfigKey, code);
            ChatModel created = mount.apply(modelConfigKey, code);
            if (created == null) {
                log.error("ROUTING 运行时挂载返回 null, graphId={}, nodeId={}, key={}, agentCode={}",
                        graphId, nodeId, modelConfigKey, code);
                throw new IllegalStateException(
                        "ROUTING 路由器模型挂载失败（null）: key=" + modelConfigKey
                                + ", agentCode=" + code + ", graphId=" + graphId);
            }
            cached = created;
            cachedAgentCode = code;
            log.info("ROUTING 运行时路由器模型就绪, graphId={}, nodeId={}, key={}, agentCode={}, type={}",
                    graphId, nodeId, modelConfigKey, code, created.getClass().getSimpleName());
            return created;
        }
    }
}
