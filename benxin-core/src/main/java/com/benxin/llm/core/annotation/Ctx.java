package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 上下文变量：不进入消息体，只用于渲染 {@link SystemPrompt} 中的占位符，
 * 并以该名字存入本次运行的 attributes，供工具与拦截器读取。
 *
 * <pre>{@code
 * String review(@User String code, @Ctx("language") String lang);
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Ctx {

    /** 变量名；留空时取参数名。 */
    String value() default "";

    /** 是否把该值放入运行时 attributes（默认是）。 */
    boolean attribute() default true;
}