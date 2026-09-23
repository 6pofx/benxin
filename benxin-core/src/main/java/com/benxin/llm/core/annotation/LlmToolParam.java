package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 描述一个工具方法参数的语义，用于生成 JSON Schema。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmToolParam {

    /** 参数名；留空时取编译期保留的真实参数名（需要 {@code -parameters}）。 */
    String value() default "";

    /** 同 {@link #value()}。 */
    String name() default "";

    /** 参数描述。 */
    String description() default "";

    /** 是否必填。 */
    boolean required() default true;

    /** 默认值（字符串形式，会按参数类型反序列化）。 */
    String defaultValue() default "";
}