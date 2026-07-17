package io.acelance.graph.dsl.annotation;

import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 从 {@link GraphNode} 注解构建 {@link GraphNodeDescriptor} 与可配置属性 schema 的工具类。
 *
 * <p>供 Tier 1 抽象基类与 Tier 2 合成适配器共用，保证两种路径产出的 descriptor 完全一致。</p>
 */
public final class GraphNodeAnnotationSupport {

    private GraphNodeAnnotationSupport() {
    }

    /** 读取类上的 {@link GraphNode} 并构建完整 descriptor（11 字段）。 */
    public static GraphNodeDescriptor buildDescriptor(Class<?> annotatedClass) {
        GraphNode ann = AnnotatedElementUtils.findMergedAnnotation(annotatedClass, GraphNode.class);
        if (ann == null) {
            throw new IllegalStateException(
                    "类 " + annotatedClass.getName() + " 未被 @GraphNode 标注，无法构建 descriptor");
        }
        return new GraphNodeDescriptor(
                ann.nodeId(),
                ann.displayName(),
                ann.category(),
                ann.description(),
                toSet(ann.inputKeys()),
                toSet(ann.outputKeys()),
                ann.supportsParallel(),
                ann.version(),
                buildConfigurableProps(ann.configurableProps()),
                ann.origin(),
                toSet(ann.permissionTags())
        );
    }

    /** 将 {@link Prop} 数组解析为 key → PropertySchema 的 Map。 */
    public static Map<String, GraphNodeDescriptor.PropertySchema> buildConfigurableProps(Prop[] props) {
        Map<String, GraphNodeDescriptor.PropertySchema> map = new LinkedHashMap<>();
        if (props == null) {
            return map;
        }
        for (Prop p : props) {
            Map<String, Object> extra = new LinkedHashMap<>();
            for (String kv : p.extra()) {
                int idx = kv.indexOf('=');
                if (idx < 0) {
                    continue;
                }
                String k = kv.substring(0, idx).trim();
                String v = kv.substring(idx + 1).trim();
                if (!k.isEmpty()) {
                    extra.put(k, coerce(v));
                }
            }
            Object def = p.defaultValue().isEmpty() ? null : coerce(p.defaultValue());
            map.put(p.key(), new GraphNodeDescriptor.PropertySchema(p.type(), p.label(), def, extra));
        }
        return map;
    }

    private static Set<String> toSet(String[] arr) {
        if (arr == null || arr.length == 0) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(arr));
    }

    /** 尝试将字符串转为数字（Long/Double），否则保留原字符串。 */
    private static Object coerce(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignore) {
            // not a long
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignore) {
            // not a double
        }
        return v;
    }
}
