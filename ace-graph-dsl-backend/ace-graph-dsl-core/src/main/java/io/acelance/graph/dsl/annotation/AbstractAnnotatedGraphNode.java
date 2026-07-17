package io.acelance.graph.dsl.annotation;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 节点 Tier 1 抽象基类：实现 {@link RegisteredGraphNode}，{@code descriptor()} 由 {@link GraphNode} 注解自动生成，
 * 子类只需实现 {@code toAction(...)}。
 *
 * <pre>{@code
 * @GraphNode(nodeId = "ragQuickAnswer", displayName = "FAQ 快速回答",
 *            inputKeys = {"q"}, outputKeys = {"reply"})
 * public class RagQuickAnswerNodeBean extends AbstractAnnotatedGraphNode {
 *     private final RagRetrievalService svc;
 *     public RagQuickAnswerNodeBean(RagRetrievalService svc) { this.svc = svc; }
 *     @Override public NodeAction toAction(NodeRuntimeContext ctx) {
 *         return new RagQuickAnswerNode(svc);
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractAnnotatedGraphNode
        implements RegisteredGraphNode, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private volatile GraphNodeDescriptor cachedDescriptor;

    @Override
    public final GraphNodeDescriptor descriptor() {
        GraphNodeDescriptor d = cachedDescriptor;
        if (d == null) {
            synchronized (this) {
                d = cachedDescriptor;
                if (d == null) {
                    d = GraphNodeAnnotationSupport.buildDescriptor(getClass());
                    cachedDescriptor = d;
                }
            }
        }
        return d;
    }

    /** 暴露 Spring 容器，供子类 {@code toAction} 中按需解析依赖。 */
    protected ApplicationContext applicationContext() {
        return applicationContext;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
