package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级系统提示词。支持 {@code {变量}} 占位符，运行时由 {@code @Ctx} 参数填充。
 *
 * <p>刻意不命名为 {@code @System}：那会与 {@link java.lang.System} 冲突，
 * 导致同一个文件里无法书写 {@code System.out}。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SystemPrompt {

    /** 系统提示词正文，支持 {@code {var}} 插值。 */
    String value() default "";

    /** 追加到系统提示词末尾的片段（例如工具使用约束），便于与主提示词分离维护。 */
    String[] append() default {};

    /** 是否把接口级 {@link LlmAgent#systemPrompt()} 追加在后面。 */
    boolean inherit() default true;
}