package io.acelance.graph.dsl.agent;

import java.util.Optional;

/**
 * skill 资源仓库 SPI：按 key 加载 skill 描述（作为附加系统指令）。
 * 默认内存实现 {@link InMemorySkillRegistry}。
 */
public interface SkillRegistry {

    Optional<String> load(String key);
}
