package io.acelance.graph.dsl.ai.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM / 流式工具相关可配置项（{@code ace.graph.dsl.llm.*}）。
 */
@ConfigurationProperties(prefix = "ace.graph.dsl.llm")
public class AceGraphDslLlmProperties {

    /**
     * 流式+工具手动多轮上限（每轮 = 一次模型 stream + 可选工具执行）。
     * 默认 30；&lt;=0 时回退为 30。
     */
    private int streamToolMaxRounds = 30;

    public int getStreamToolMaxRounds() {
        return streamToolMaxRounds;
    }

    public void setStreamToolMaxRounds(int streamToolMaxRounds) {
        this.streamToolMaxRounds = streamToolMaxRounds;
    }

    /** 规范化后的正整数上限。 */
    public int resolvedStreamToolMaxRounds() {
        return streamToolMaxRounds > 0 ? streamToolMaxRounds : 30;
    }
}
