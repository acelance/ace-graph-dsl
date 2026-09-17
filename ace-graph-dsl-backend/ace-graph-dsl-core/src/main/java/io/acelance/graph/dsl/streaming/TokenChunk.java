package io.acelance.graph.dsl.streaming;

import com.alibaba.cloud.ai.graph.streaming.OutputType;

import java.util.Map;

/**
 * 一个 LLM 流式 token 片段（框架无关），经 {@link GraphStreamBridge} 由节点推给控制器。
 *
 * <p>{@code responseKind} 为流式类型标签（BIZ/OUTPUT/扩展），仅供业务
 * {@link io.acelance.graph.dsl.execution.StreamingChunkFormatter} 读取；默认下发协议
 * <b>不</b>自动附带该字段（§9.6.3）。</p>
 *
 * <p>{@code attrs} 为节点透传的可选元数据（如业务附加参数原文），供平台 SSE 适配器使用，
 * 避免再次按 graphId 查定义时与运行时编译图不一致。</p>
 *
 * @param nodeId       产出节点
 * @param token        文本片段
 * @param outputType   框架 OutputType
 * @param responseKind 流式响应类型 KEY，可空
 * @param last         是否本段结束
 * @param attrs        透传属性，可空（规范化为空 Map）
 */
public record TokenChunk(
        String nodeId,
        String token,
        OutputType outputType,
        String responseKind,
        boolean last,
        Map<String, Object> attrs
) {
    public TokenChunk {
        attrs = (attrs == null || attrs.isEmpty()) ? Map.of() : Map.copyOf(attrs);
    }

    /** 兼容旧调用：无 responseKind / attrs */
    public TokenChunk(String nodeId, String token, OutputType outputType, boolean last) {
        this(nodeId, token, outputType, null, last, Map.of());
    }

    /** 兼容旧调用：无 attrs */
    public TokenChunk(String nodeId, String token, OutputType outputType, String responseKind, boolean last) {
        this(nodeId, token, outputType, responseKind, last, Map.of());
    }

    /** 业务附加参数开关（{@link #attrs}） */
    public static final String ATTR_ENABLE_BIZ_PARAMS = "enableBizParams";
    /** 附加参数解释器 id */
    public static final String ATTR_BIZ_PARAM_INTERPRETER_ID = "bizParamInterpreterId";
    /** 附加参数原文 */
    public static final String ATTR_BIZ_PARAM_RAW = "bizParamRaw";
}
