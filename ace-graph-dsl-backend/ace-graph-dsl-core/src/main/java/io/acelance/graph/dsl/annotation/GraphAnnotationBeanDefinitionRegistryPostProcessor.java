package io.acelance.graph.dsl.annotation;

import io.acelance.graph.dsl.registry.RegisteredEdgeDispatcher;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Arrays;

/**
 * 注解化注册核心：在 BeanDefinition 注册阶段扫描已存在的（组件扫描得到的）Bean 定义，
 * 对带 {@link GraphNode#action()} / {@link EdgeDispatcher#action()} 且自身未实现对应接口的注解类，
 * 合成对应的 {@code RegisteredGraphNode} / {@link RegisteredEdgeDispatcher} Bean（Tier 2，零样板）。
 *
 * <p>设计为 {@link Ordered}（非 PriorityOrdered），确保在 Spring 自身的
 * {@code ConfigurationClassPostProcessor}（负责 {@code @ComponentScan}）之后运行，
 * 此时被扫描的注解类 Bean 定义已就绪。</p>
 *
 * <p>CheckpointSaver 仅支持 Tier 1（注解 + 实现 {@code CheckpointSaverProvider}），由
 * {@code CheckpointSaverRegistry} 经 {@code ObjectProvider} 直接收集，无需在此合成。</p>
 */
public class GraphAnnotationBeanDefinitionRegistryPostProcessor
        implements BeanDefinitionRegistryPostProcessor, Ordered {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition bd = registry.getBeanDefinition(beanName);
            String className = bd.getBeanClassName();
            if (className == null || className.startsWith("org.springframework")) {
                continue;
            }
            Class<?> clazz = safeLoad(className);
            if (clazz == null) {
                continue;
            }

            GraphNode gn = AnnotatedElementUtils.findMergedAnnotation(clazz, GraphNode.class);
            if (gn != null) {
                if (!RegisteredGraphNode.class.isAssignableFrom(clazz) && gn.action() != Void.class) {
                    registerAdapter(registry, AnnotatedGraphNodeAdapter.class,
                            clazz, gn.action(), "graphNodeAdapter");
                }
                continue;
            }

            EdgeDispatcher ed = AnnotatedElementUtils.findMergedAnnotation(clazz, EdgeDispatcher.class);
            if (ed != null) {
                if (!RegisteredEdgeDispatcher.class.isAssignableFrom(clazz) && ed.action() != Void.class) {
                    registerAdapter(registry, AnnotatedEdgeDispatcherAdapter.class,
                            clazz, ed.action(), "edgeDispatcherAdapter");
                }
                // else：Tier 1（自身实现接口），由对应 Registry 直接收集，无需合成
            }
            // CheckpointSaver：仅 Tier 1，此处不合成
        }
    }

    private void registerAdapter(BeanDefinitionRegistry registry, Class<?> adapterClass,
                                 Class<?> annotatedClass, Class<?> actionClass, String prefix) {
        String beanName = prefix + "_" + actionClass.getSimpleName();
        if (registry.containsBeanDefinition(beanName)) {
            return;
        }
        RootBeanDefinition def = new RootBeanDefinition(adapterClass);
        def.getConstructorArgumentValues().addIndexedArgumentValue(0, annotatedClass);
        def.getConstructorArgumentValues().addIndexedArgumentValue(1, actionClass);
        def.setAutowireMode(RootBeanDefinition.AUTOWIRE_NO);
        registry.registerBeanDefinition(beanName, def);
    }

    private Class<?> safeLoad(String className) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 无需处理
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
