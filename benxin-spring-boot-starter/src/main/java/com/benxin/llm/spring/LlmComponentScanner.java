package com.benxin.llm.spring;

import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.loop.AgentLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Set;

/**
 * 扫描"打了注解但没交给 Spring 管理"的组件。
 *
 * <p>本心的注解承诺是"打个注解就能用"，因此 {@code @LlmLoop} / {@code @LlmGuard}
 * 标注的类不应该再要求用户额外补一个 {@code @Component}。这里把它们补注册成 bean，
 * 至于已经在容器里的（用户自己加了 {@code @Component}）则跳过，不与其争抢。</p>
 *
 * <p>被 {@link LlmAgentRegistrar} 与 {@link LlmComponentRegistrar} 共用，
 * 避免"显式指定包"和"自动推断包"两条路径各写一遍扫描逻辑。</p>
 */
final class LlmComponentScanner implements ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(LlmComponentScanner.class);

    private ResourceLoader resourceLoader;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 扫描并注册注解组件。
     *
     * @return 实际注册的 bean 数量
     */
    int registerAnnotatedComponents(BeanDefinitionRegistry registry, Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return 0;
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        scanner.addIncludeFilter(new AnnotationTypeFilter(LlmLoop.class, true, false));
        scanner.addIncludeFilter(new AnnotationTypeFilter(LlmGuard.class, true, false));

        int registered = 0;
        for (String basePackage : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                if (registerOne(registry, candidate.getBeanClassName())) {
                    registered++;
                }
            }
        }
        if (registered > 0) {
            log.info("[benxin] 自动注册了 {} 个 @LlmLoop / @LlmGuard 组件", registered);
        }
        return registered;
    }

    private boolean registerOne(BeanDefinitionRegistry registry, String className) {
        Class<?> type;
        try {
            type = ClassUtils.forName(className,
                    resourceLoader == null ? getClass().getClassLoader() : resourceLoader.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            log.warn("[benxin] 无法加载注解组件 {}: {}", className, e.toString());
            return false;
        }

        // 注解打错了对象就明确报错，而不是注册一个永远用不上的 bean
        boolean isLoop = AgentLoop.class.isAssignableFrom(type);
        boolean isInterceptor = AgentInterceptor.class.isAssignableFrom(type);
        if (!isLoop && !isInterceptor) {
            log.warn("[benxin] {} 上的 @LlmLoop/@LlmGuard 被忽略：它既没有实现 AgentLoop 也没有实现 AgentInterceptor",
                    className);
            return false;
        }

        String beanName = java.beans.Introspector.decapitalize(type.getSimpleName());
        if (registry.containsBeanDefinition(beanName)) {
            // 用户已经用 @Component 等方式登记过，尊重既有定义
            log.debug("[benxin] {} 已是容器 bean [{}]，跳过自动注册", className, beanName);
            return false;
        }
        registry.registerBeanDefinition(beanName,
                BeanDefinitionBuilder.genericBeanDefinition(type).getBeanDefinition());
        log.debug("[benxin] 注册注解组件 [{}] ← {}", beanName, className);
        return true;
    }
}