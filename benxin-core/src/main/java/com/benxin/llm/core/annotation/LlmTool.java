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

    /**
     * 是否需要在执行前征求人工审批。
     *
     * <p>写库、发消息、调用外部系统一类有副作用的注解工具应当设为 {@code true}
     * （等价于手写 {@code ToolCallback} 覆写 {@code requiresApproval()}）。</p>
     *
     * <p>类上与方法上都标注时取"安全侧"：任一处为 {@code true} 即为 {@code true}。</p>
     */
    boolean requiresApproval() default false;

    /**
     * 同一轮里多个工具调用是否可以并行执行。
     *
     * <p>默认 {@code true}。有副作用或依赖共享状态的工具应设为 {@code false} 以串行执行。</p>
     *
     * <p>类上与方法上都标注时取"安全侧"：任一处为 {@code false} 即为 {@code false}。</p>
     */
    boolean parallelSafe() default true;
}