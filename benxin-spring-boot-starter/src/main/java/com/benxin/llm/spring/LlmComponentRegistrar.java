package com.benxin.llm.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在应用主包下自动发现 {@code @LlmLoop} 与 {@code @LlmGuard} 组件。
 *
 * <p>做成 {@link BeanDefinitionRegistryPostProcessor} 是必需的：这些组件必须在
 * {@code llmLoopRegistry} 这类普通 bean 实例化<b>之前</b>进入容器，
 * 否则 {@code ObjectProvider<AgentLoop>} 会因为"扫描还没发生"而看不到它们。</p>
 *
 * <p>扫描范围取 {@code @SpringBootApplication} 所在包（Spring Boot 的
 * {@code AutoConfigurationPackages}），因此常规项目零配置即可生效。</p>
 */
public class LlmComponentRegistrar implements BeanDefinitionRegistryPostProcessor,
        BeanFactoryAware, ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(LlmComponentRegistrar.class);

    private final LlmComponentScanner scanner = new LlmComponentScanner();
    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        scanner.setResourceLoader(resourceLoader);
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Set<String> packages = new LinkedHashSet<>();
        if (beanFactory != null) {
            try {
                List<String> autoPackages = AutoConfigurationPackages.get(beanFactory);
                packages.addAll(autoPackages);
            } catch (IllegalStateException e) {
                log.debug("[benxin] 未检测到 AutoConfigurationPackages，跳过 @LlmLoop/@LlmGuard 自动扫描");
            }
        }
        scanner.registerAnnotatedComponents(registry, packages);
    }

    @Override
    public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        // 无需额外处理
    }
}