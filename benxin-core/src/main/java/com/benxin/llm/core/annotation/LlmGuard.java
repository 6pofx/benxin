package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式拦截器：标记在 {@code AgentInterceptor} 实现类上即被注册进全局拦截器链。
 * 若要只作用于单个 Agent，改用 {@link LlmAgent#interceptors()}。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmGuard {

    /** 是否全局生效。 */
    boolean global() default true;

    /** 只对这些 Agent 生效（为空表示不限制）。 */
    String[] agents() default {};
}