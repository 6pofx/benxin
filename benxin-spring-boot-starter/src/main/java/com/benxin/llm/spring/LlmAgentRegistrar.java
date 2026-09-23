package com.benxin.llm.spring;

import com.benxin.llm.core.annotation.LlmAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫描带 {@code @LlmAgent} 的接口，并为每一个注册一个 {@link LlmAgentFactoryBean}。
 *
 * <p>两个细节值得说明：</p>
 * <ul>
 *   <li>Spring 的组件扫描默认**排除接口**，因此这里覆写 {@code isCandidateComponent}
 *       让接口也能成为候选组件。</li>
 *   <li>扫描范围优先取 {@code @EnableLlmAgents(basePackages=...)}；未指定时回退到
 *       {@code @SpringBootApplication} 所在包，实现"零配置可用"。</li>
 * </ul>
 */
public class LlmAgentRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentRegistrar.class);

    private BeanFactory beanFactory;
    private ResourceLoader resourceLoader;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        Set<String> packages = resolveBasePackages(importingClassMetadata);
        if (packages.isEmpty()) {
            log.warn("[benxin] 未找到可扫描的包，@LlmAgent 接口不会被注册；"
                    + "请显式使用 @EnableLlmAgents(basePackages = \"...\")");
            return;
        }

        InterfaceScanner scanner = new InterfaceScanner();
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }

        int count = 0;
        for (String basePackage : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                String className = candidate.getBeanClassName();
                Class<?> agentInterface;
                try {
                    agentInterface = ClassUtils.forName(className,
                            resourceLoader == null ? getClass().getClassLoader() : resourceLoader.getClassLoader());
                } catch (ClassNotFoundException | LinkageError e) {
                    log.warn("[benxin] 无法加载 @LlmAgent 接口 {}: {}", className, e.toString());
                    continue;
                }
                if (registerAgent(registry, agentInterface)) {
                    count++;
                }
            }
        }
        log.info("[benxin] 从 {} 个包中注册了 {} 个 @LlmAgent 接口", packages.size(), count);

        // 同一批包里若还有 @LlmLoop / @LlmGuard 标注的类，一并注册 ——
        // 用户显式指定了扫描范围，就应该把"本心的注解"整体覆盖到，
        // 而不是只认 @LlmAgent 一种。
        LlmComponentScanner componentScanner = new LlmComponentScanner();
        if (resourceLoader != null) {
            componentScanner.setResourceLoader(resourceLoader);
        }
        componentScanner.registerAnnotatedComponents(registry, packages);
    }

    private boolean registerAgent(BeanDefinitionRegistry registry, Class<?> agentInterface) {
        LlmAgent annotation = agentInterface.getAnnotation(LlmAgent.class);
        if (annotation == null) {
            return false;
        }
        String name = annotation.value().isBlank() ? annotation.name() : annotation.value();
        if (name.isBlank()) {
            name = Character.toLowerCase(agentInterface.getSimpleName().charAt(0))
                    + agentInterface.getSimpleName().substring(1);
        }
        String beanName = name;
        int suffix = 1;
        while (registry.containsBeanDefinition(beanName)) {
            beanName = name + "#" + suffix++;
        }
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(LlmAgentFactoryBean.class)
                .addConstructorArgValue(agentInterface)
                .addConstructorArgValue(name)
                .addConstructorArgValue(beanFactory);
        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        log.debug("[benxin] 注册 @LlmAgent 接口 {} 为 bean [{}]", agentInterface.getName(), beanName);
        return true;
    }

    private Set<String> resolveBasePackages(AnnotationMetadata metadata) {
        Set<String> packages = new LinkedHashSet<>();
        if (metadata != null && metadata.hasAnnotation(EnableLlmAgents.class.getName())) {
            AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                    metadata.getAnnotationAttributes(EnableLlmAgents.class.getName()));
            if (attributes != null) {
                for (String pkg : attributes.getStringArray("basePackages")) {
                    if (!pkg.isBlank()) {
                        packages.add(pkg);
                    }
                }
                for (Class<?> clazz : attributes.getClassArray("basePackageClasses")) {
                    packages.add(ClassUtils.getPackageName(clazz));
                }
            }
        }
        if (packages.isEmpty() && beanFactory != null) {
            try {
                List<String> autoPackages = AutoConfigurationPackages.get(beanFactory);
                packages.addAll(autoPackages);
            } catch (IllegalStateException e) {
                // 非 Spring Boot 应用或未使用 @AutoConfigurationPackage
            }
        }
        if (packages.isEmpty() && metadata != null) {
            String className = metadata.getClassName();
            if (className != null && !className.isBlank()) {
                packages.add(ClassUtils.getPackageName(className));
            }
        }
        return packages;
    }

    /** 允许接口成为候选组件的扫描器。 */
    private static final class InterfaceScanner extends ClassPathScanningCandidateComponentProvider {

        InterfaceScanner() {
            super(false);
            addIncludeFilter(new AnnotationTypeFilter(LlmAgent.class, true, true));
        }

        @Override
        protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
            return beanDefinition.getMetadata().isIndependent()
                    && (beanDefinition.getMetadata().isInterface() || beanDefinition.getMetadata().isAbstract());
        }
    }
}