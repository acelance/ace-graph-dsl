package io.acelance.graph.dsl.annotation;

import com.alibaba.cloud.ai.graph.action.EdgeAction;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredEdgeDispatcher;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Arrays;
import java.util.Set;

/**
 * Tier 2 合成适配器：对应带 {@link EdgeDispatcher#action()} 的注解类。
 * 与 {@link AnnotatedGraphNodeAdapter} 同理，{@code toAction} 通过 autowire 创建 action 实例。
 */
public class AnnotatedEdgeDispatcherAdapter implements RegisteredEdgeDispatcher, ApplicationContextAware {

    private final Class<?> annotatedClass;
    private final Class<?> actionClass;
    private ApplicationContext applicationContext;

    public AnnotatedEdgeDispatcherAdapter(Class<?> annotatedClass, Class<?> actionClass) {
        this.annotatedClass = annotatedClass;
        this.actionClass = actionClass;
    }

    @Override
    public String dispatcherId() {
        return requireAnnotation().dispatcherId();
    }

    @Override
    public Set<String> possibleTargets() {
        return Set.copyOf(Arrays.asList(requireAnnotation().possibleTargets()));
    }

    private EdgeDispatcher requireAnnotation() {
        EdgeDispatcher ann = AnnotatedElementUtils.findMergedAnnotation(annotatedClass, EdgeDispatcher.class);
        if (ann == null) {
            throw new IllegalStateException(
                    "类 " + annotatedClass.getName() + " 未被 @EdgeDispatcher 标注");
        }
        return ann;
    }

    @Override
    @SuppressWarnings("unchecked")
    public EdgeAction toAction(NodeRuntimeContext ctx) {
        AutowireCapableBeanFactory factory = applicationContext.getAutowireCapableBeanFactory();
        return (EdgeAction) factory.autowire(actionClass, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
