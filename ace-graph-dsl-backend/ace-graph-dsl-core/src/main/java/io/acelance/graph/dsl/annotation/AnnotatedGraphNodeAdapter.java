package io.acelance.graph.dsl.annotation;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Tier 2 合成适配器：由 {@link GraphAnnotationBeanDefinitionRegistryPostProcessor} 创建，
 * 对应一个带 {@link GraphNode#action()} 的注解类。无需手写 {@code RegisteredGraphNode} 实现类。
 *
 * <ul>
 *   <li>{@code descriptor()}：从注解类上的 {@link GraphNode} 构建。</li>
 *   <li>{@code toAction(...)}：通过 {@link AutowireCapableBeanFactory#autowire(Class, int, boolean)}
 *       按构造器解析依赖，创建一个全新的 action 实例（与原手写 {@code new X(deps)} 行为一致）。</li>
 * </ul>
 */
public class AnnotatedGraphNodeAdapter implements RegisteredGraphNode, ApplicationContextAware {

    private final Class<?> annotatedClass;
    private final Class<?> actionClass;
    private ApplicationContext applicationContext;
    private volatile GraphNodeDescriptor cachedDescriptor;

    public AnnotatedGraphNodeAdapter(Class<?> annotatedClass, Class<?> actionClass) {
        this.annotatedClass = annotatedClass;
        this.actionClass = actionClass;
    }

    @Override
    public GraphNodeDescriptor descriptor() {
        GraphNodeDescriptor d = cachedDescriptor;
        if (d == null) {
            synchronized (this) {
                d = cachedDescriptor;
                if (d == null) {
                    d = GraphNodeAnnotationSupport.buildDescriptor(annotatedClass);
                    cachedDescriptor = d;
                }
            }
        }
        return d;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeAction toAction(NodeRuntimeContext ctx) {
        AutowireCapableBeanFactory factory = applicationContext.getAutowireCapableBeanFactory();
        return (NodeAction) factory.autowire(actionClass, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
