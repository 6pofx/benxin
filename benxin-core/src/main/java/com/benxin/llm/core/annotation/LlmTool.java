package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式工具：把一个普通 Java 方法交给模型调用。
 * 参数类型会被反射成 JSON Schema，返回值会被序列化为工具结果文本。
 *
 * <pre>{@code
 * @LlmTool(name = "get_weather", description = "查询城市天气")
 * public Weather get(@LlmToolParam(description = "城市名") String city) { ... }
 * }</pre>
 *
 * <p>标注在类上表示"该类所有 public 方法都是工具"。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmTool {

    /** 工具名（暴露给模型）；留空时取方法名。 */
    String name() default "";

    /** 同 {@link #name()}。 */
    String value() default "";

    /** 工具描述（强烈建议填写，模型据此决定是否调用）。 */
    String description() default "";

    /** 返回值是否直接作为 Agent 最终答案返回，而不再交回模型。 */
    boolean returnDirect() default false;

    /** 是否启用；便于灰度或踩刹车。 */
    boolean enabled() default true;
}