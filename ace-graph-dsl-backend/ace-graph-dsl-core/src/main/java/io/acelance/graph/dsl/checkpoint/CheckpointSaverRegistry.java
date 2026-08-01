package io.acelance.graph.dsl.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checkpoint saver 注册表：按 {@code compile.saver} 类型解析对应的 {@link CheckpointSaverProvider}。
 *
 * <p>替代此前硬编码「全部落回 memory」的行为：未注册的类型将按 {@code fallbackToMemory} 决定
 * 「告警并回退 memory」或「抛出明确异常」，避免静默使用错误的后端。</p>
 */
public class CheckpointSaverRegistry {

    private static final Logger log = LoggerFactory.getLogger(CheckpointSaverRegistry.class);

    private final Map<String, CheckpointSaverProvider> providers = new LinkedHashMap<>();
    private final boolean fallbackToMemory;

    /**
     * Saver 单例缓存：按 type 缓存 {@link BaseCheckpointSaver} 实例。
     *
     * <p>G4 子图内 HITL 硬约束：父图与子图的 {@code CompileConfig.checkpointSaver()}
     * 必须为同一实例（库用 {@code ==} 校验）。缓存确保 {@link #resolve(String)} 对同一
     * type 始终返回同一实例，使父子图共享 saver。</p>
     */
    private final Map<String, BaseCheckpointSaver> singletonCache = new LinkedHashMap<>();

    public CheckpointSaverRegistry(Collection<CheckpointSaverProvider> providers, boolean fallbackToMemory) {
        this.fallbackToMemory = fallbackToMemory;
        if (providers != null) {
            for (CheckpointSaverProvider p : providers) {
                if (p == null || p.type() == null) {
                    continue;
                }
                this.providers.put(normalize(p.type()), p);
            }
        }
        // 兜底保证 memory 始终可用
        this.providers.putIfAbsent(MemoryCheckpointSaverProvider.TYPE, new MemoryCheckpointSaverProvider());
        log.info("Checkpoint saver 已注册类型: {}", this.providers.keySet());
    }

    /** 已注册的全部 saver 类型。 */
    public Set<String> registeredTypes() {
        return this.providers.keySet();
    }

    /** 是否支持指定类型。 */
    public boolean supports(String type) {
        return providers.containsKey(normalize(type));
    }

    /**
     * 按类型解析并创建一个 checkpoint saver（单例缓存）。
     *
     * <p>同一 type 的多次调用返回同一实例，满足 G4 子图内 HITL 的父子 saver {@code ==}
     * 约束。首次调用时经 {@link CheckpointSaverProvider#create()} 创建并缓存。</p>
     *
     * @param type saver 类型；{@code null}/空白按 memory 处理
     * @return checkpoint saver 实例（同一 type 始终同一实例）
     * @throws IllegalArgumentException 类型未注册且 {@code fallbackToMemory=false} 时
     */
    public BaseCheckpointSaver resolve(String type) {
        String key = normalize(type);
        BaseCheckpointSaver cached = singletonCache.get(key);
        if (cached != null) {
            return cached;
        }
        CheckpointSaverProvider provider = providers.get(key);
        BaseCheckpointSaver saver;
        if (provider != null) {
            saver = provider.create();
        } else if (fallbackToMemory) {
            log.warn("未注册的 checkpoint saver 类型 '{}'，已回退到 memory（可注册 CheckpointSaverProvider 扩展）。当前可用: {}",
                    key, providers.keySet());
            saver = new MemorySaver();
        } else {
            throw new IllegalArgumentException(
                    "未注册的 checkpoint saver 类型: " + key + "，可用类型: " + providers.keySet());
        }
        singletonCache.put(key, saver);
        return saver;
    }

    private static String normalize(String type) {
        if (type == null || type.isBlank()) {
            return MemoryCheckpointSaverProvider.TYPE;
        }
        return type.trim().toLowerCase();
    }

    /** 仅含内存型 saver 的默认注册表（无 Spring 环境时的兜底）。 */
    public static CheckpointSaverRegistry defaults() {
        return new CheckpointSaverRegistry(List.of(new MemoryCheckpointSaverProvider()), true);
    }
}
