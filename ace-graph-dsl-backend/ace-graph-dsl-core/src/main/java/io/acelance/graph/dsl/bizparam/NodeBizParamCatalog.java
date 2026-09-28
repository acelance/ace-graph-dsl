package io.acelance.graph.dsl.bizparam;

import java.util.Comparator;
import java.util.List;

/**
 * 节点业务附加参数解释器目录（设计器 API）。
 */
@FunctionalInterface
public interface NodeBizParamCatalog {

    List<NodeBizParamItem> list(String graphId);

    Comparator<NodeBizParamItem> ORDER = Comparator
            .comparingInt(NodeBizParamItem::order)
            .thenComparing(NodeBizParamItem::id, Comparator.nullsLast(String::compareTo));
}
