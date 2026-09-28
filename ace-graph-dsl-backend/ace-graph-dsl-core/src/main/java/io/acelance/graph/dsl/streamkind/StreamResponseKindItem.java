package io.acelance.graph.dsl.streamkind;

/**
 * 流式类型目录项（设计期下拉 / 运行期归一共用）。
 *
 * @param code  稳定 KEY
 * @param label 展示名
 * @param order 排序（升序；同 order 再按 code 字典序）
 */
public record StreamResponseKindItem(String code, String label, int order) {
}
