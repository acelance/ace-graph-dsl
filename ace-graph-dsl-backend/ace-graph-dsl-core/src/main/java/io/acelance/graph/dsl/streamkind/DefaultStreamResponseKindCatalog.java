package io.acelance.graph.dsl.streamkind;

import java.util.List;

/**
 * 默认流式类型目录：BIZ / OUTPUT（忽略 graphId，业务可覆盖 Bean）。
 */
public class DefaultStreamResponseKindCatalog implements StreamResponseKindCatalog {

    private final List<StreamResponseKindItem> items = List.of(
            new StreamResponseKindItem("BIZ", "业务处理", 10),
            new StreamResponseKindItem("OUTPUT", "结果输出", 20)
    );

    @Override
    public List<StreamResponseKindItem> list(String graphId) {
        return items;
    }
}
