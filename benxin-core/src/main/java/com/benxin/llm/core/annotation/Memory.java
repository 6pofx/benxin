package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 会话标识参数：其值作为 sessionId 用于装载/保存 {@code MemoryStore} 中的历史。
 *
 * <pre>{@code
 * String chat(@User String q, @Memory String sessionId);
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Memory {
}