package io.acelance.graph.dsl.streamkind;

import java.util.Comparator;
import java.util.List;

/**
 * 流式响应类型目录（设计期 API + 运行期归一同源）。
 */
@FunctionalInterface
public interface StreamResponseKindCatalog {

    /** 同一 graphId 须返回同一列表（幂等、排序稳定）；默认实现可忽略 graphId */
    List<StreamResponseKindItem> list(String graphId);

    /** 与 UI 一致的排序：order 升序，同 order 再按 code */
    Comparator<StreamResponseKindItem> ORDER = Comparator
            .comparingInt(StreamResponseKindItem::order)
            .thenComparing(StreamResponseKindItem::code, Comparator.nullsLast(String::compareTo));
}
