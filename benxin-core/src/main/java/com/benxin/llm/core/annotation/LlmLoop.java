package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 Loop：标记在一个 {@code AgentLoop} 实现类上，即被注册进 Loop 注册表，
 * 从而可以被 {@link LlmAgent#loop()} 或 {@code llm.agent.default-loop} 按名字选用。
 *
 * <pre>{@code
 * @LlmLoop("my-loop")
 * public class MyLoop extends AbstractAgentLoop { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmLoop {

    /** Loop 名；留空时取类简单名（去掉 Loop 后缀）并转 kebab-case。 */
    String value() default "";

    /** 是否为全局默认 Loop（同时存在多个时按 {@link #order()} 取最小者）。 */
    boolean defaultLoop() default false;

    /** 覆盖顺序，值越小优先级越高。 */
    int order() default 0;
}