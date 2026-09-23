package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 方法级/类级重试策略：由 starter 自动织入重试拦截器，无需手写 try-catch。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmRetry {

    /** 最大尝试次数（含首次）。 */
    int maxAttempts() default 3;

    /** 首次退避毫秒数，按 {@link #multiplier()} 指数增长。 */
    long backoffMillis() default 500;

    /** 退避倍数。 */
    double multiplier() default 2.0;

    /** 是否对工具执行失败也重试。 */
    boolean includeTools() default false;
}