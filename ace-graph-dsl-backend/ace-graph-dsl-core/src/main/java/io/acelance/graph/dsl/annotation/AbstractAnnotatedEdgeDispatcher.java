package io.acelance.graph.dsl.annotation;

import com.alibaba.cloud.ai.graph.action.EdgeAction;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredEdgeDispatcher;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Arrays;
import java.util.Set;

/**
 * 条件边分发器 Tier 1 抽象基类：实现 {@link RegisteredEdgeDispatcher}，
 * {@code dispatcherId()} / {@code possibleTargets()} 由 {@link EdgeDispatcher} 注解自动生成，
 * 子类只需实现 {@code toAction(...)}。
 */
public abstract class AbstractAnnotatedEdgeDispatcher
        implements RegisteredEdgeDispatcher, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public final String dispatcherId() {
        return requireAnnotation().dispatcherId();
    }

    @Override
    public final Set<String> possibleTargets() {
        return Set.copyOf(Arrays.asList(requireAnnotation().possibleTargets()));
    }

    private EdgeDispatcher requireAnnotation() {
        EdgeDispatcher ann = AnnotatedElementUtils.findMergedAnnotation(getClass(), EdgeDispatcher.class);
        if (ann == null) {
            throw new IllegalStateException(
                    "类 " + getClass().getName() + " 未被 @EdgeDispatcher 标注");
        }
        return ann;
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
