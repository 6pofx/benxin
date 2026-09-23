package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式模型：标记在一个 {@code LlmModel} 实现类上，以指定名字注册进模型注册表。
 * 不标注解时，Spring 容器中的 {@code LlmModel} bean 也会以其 {@code name()} 自动注册。
 *
 * <p>命名说明：接口叫 {@code LlmModel}，注解叫 {@code LlmModelDef}，避免同一文件同时
 * 导入类型与注解时的名字冲突。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmModelDef {

    /** 模型名；留空时取 {@code LlmModel#name()}。 */
    String value() default "";

    /** 是否为全局默认模型（多个时按 {@link #order()} 取最小者）。 */
    boolean defaultModel() default false;

    /** 覆盖顺序，值越小优先级越高。 */
    int order() default 0;
}