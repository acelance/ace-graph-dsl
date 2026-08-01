package io.acelance.graph.dsl.agent;

import java.util.Optional;

/**
 * prompt 资源仓库 SPI：按 key 加载 prompt 模板文本。
 * 默认内存实现 {@link InMemoryPromptRepository}；可替换为 DB / 配置中心等。
 */
public interface PromptRepository {

    Optional<String> load(String key);
}
