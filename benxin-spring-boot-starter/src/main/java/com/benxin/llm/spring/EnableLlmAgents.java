package com.benxin.llm.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开启 {@code @LlmAgent} 接口扫描。
 *
 * <p>本心的自动配置会尽力从 {@code @SpringBootApplication} 所在包自动推断扫描范围，
 * 因此多数项目无需显式使用本注解；只有当 Agent 接口位于别处、或需要限定扫描范围时才需要。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(LlmAgentRegistrar.class)
public @interface EnableLlmAgents {

    /** 要扫描的包；留空表示从主应用类所在包推断。 */
    String[] basePackages() default {};

    /** 按类指定扫描起点，便于类型安全地书写。 */
    Class<?>[] basePackageClasses() default {};
}