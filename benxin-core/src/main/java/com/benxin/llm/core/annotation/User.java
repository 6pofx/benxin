package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记该参数作为 user 消息内容。这是方法参数的默认语义，可省略不写。
 * 多个 {@code @User} 参数会按声明顺序拼接。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface User {

    /** 前缀，会拼接在参数值之前。 */
    String prefix() default "";

    /** 后缀，会拼接在参数值之后。 */
    String suffix() default "";
}