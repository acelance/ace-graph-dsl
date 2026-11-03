package io.acelance.graph.dsl.streaming;

import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将父 {@link SaaWorkflowSpec} 的业务附加参数转为 {@link TokenChunk#attrs()}（G-STREAM-INNER）。
 *
 * <p>键与 GENERIC {@code GenericAgentNode#streamAttrsFromSpec} 对齐，供宿主 SSE 适配器优先消费。
 * 未启用 bizParam 时返回空 Map（存量图行为不变）。</p>
 */
public final class SaaStreamAttrs {

    private static final Logger log = LoggerFactory.getLogger(SaaStreamAttrs.class);

    private SaaStreamAttrs() {
    }

    /**
     * 父 SAA Spec → TokenChunk.attrs。
     *
     * @param spec 父节点规格，可空
     * @return 不可变 attrs；未启用时为空
     */
    public static Map<String, Object> fromSpec(SaaWorkflowSpec spec) {
        if (spec == null || !spec.enableBizParams()) {
            return Map.of();
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(TokenChunk.ATTR_ENABLE_BIZ_PARAMS, Boolean.TRUE);
        if (spec.bizParamInterpreterId() != null && !spec.bizParamInterpreterId().isBlank()) {
            attrs.put(TokenChunk.ATTR_BIZ_PARAM_INTERPRETER_ID, spec.bizParamInterpreterId().trim());
        }
        if (spec.bizParamRaw() != null) {
            attrs.put(TokenChunk.ATTR_BIZ_PARAM_RAW, spec.bizParamRaw());
        }
        log.debug("SaaStreamAttrs 已生成: enableBizParams=true, interpreterId={}, rawLen={}",
                attrs.get(TokenChunk.ATTR_BIZ_PARAM_INTERPRETER_ID),
                spec.bizParamRaw() == null ? 0 : spec.bizParamRaw().length());
        return Map.copyOf(attrs);
    }
}
