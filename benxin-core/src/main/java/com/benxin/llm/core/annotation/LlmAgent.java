package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 Agent：标记在一个接口上，本心会在容器启动时为该接口生成动态代理实现，
 * 直接 {@code @Autowired} 注入即可调用，方法调用会被翻译成一次 Agent 运行。
 *
 * <pre>{@code
 * @LlmAgent(model = "deepseek", loop = "claude-code", tools = {WeatherTools.class})
 * public interface CodeAssistant {
 *     @SystemPrompt("你是资深 Java 架构师")
 *     String chat(@User String question);
 * }
 * }</pre>
 *
 * <p>所有属性留空时回落到 {@code llm.agent.*} 全局默认值，因此"零配置"也能工作。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmAgent {

    /** Agent 名称；留空时取接口简单类名并首字母小写。 */
    String value() default "";

    /** 同 {@link #value()}，显式语义别名。 */
    String name() default "";

    /** 使用的模型名（对应 {@code llm.models.*} 的 key 或 LlmModel#name()）；留空用全局默认模型。 */
    String model() default "";

    /** 使用的 Agent Loop 名（如 {@code claude-code} / {@code codex} / {@code dsh-minimal} / {@code react}）；留空用全局默认 Loop。 */
    String loop() default "";

    /** 携带 {@link LlmTool} 方法的类，其工具会被装配进该 Agent。 */
    Class<?>[] tools() default {};

    /** 直接引用已注册的工具名。 */
    String[] toolNames() default {};

    /** 默认系统提示词；方法级 {@link SystemPrompt} 优先。 */
    String systemPrompt() default "";

    /** 最大步数（一次调用内模型往返的上限）；{@code -1} 表示使用全局默认。 */
    int maxSteps() default -1;

    /** 是否对模型输出做流式解析。 */
    boolean stream() default false;

    /** 是否启用会话记忆（同一 sessionId 的历史消息自动续接）。 */
    boolean memory() default true;

    /** 追加的拦截器实现类，按 {@code order()} 升序执行。 */
    Class<?>[] interceptors() default {};

    /** 该 Agent 可派生的子代理名，供 claude-code 等支持子代理的 Loop 使用。 */
    String[] subAgents() default {};

    /** 采样温度；{@code -1} 表示不下发该参数。 */
    double temperature() default -1;

    /** 单次回复的最大 token；{@code -1} 表示不下发该参数。 */
    int maxTokens() default -1;
}