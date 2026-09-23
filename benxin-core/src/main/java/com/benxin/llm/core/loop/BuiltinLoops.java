package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;

import java.util.List;

/**
 * 内置 Loop 的登记处。
 *
 * <p>集中在一处注册有两个好处：一是"框架到底带了哪些 Loop"一眼可见；
 * 二是新写一个 Loop 时只需在这里加一行，而不用去翻自动配置。</p>
 *
 * <p>注册名优先取 {@link LlmLoop} 注解的值，取不到才回落到 {@code name()} ——
 * 这样"注解上写的名字"永远是唯一的对外契约，避免两处定义漂移。</p>
 */
public final class BuiltinLoops {

    /** 默认 Loop 名。选它是因为它最简、最可预测，也最能体现"一切可替换"。 */
    public static final String DEFAULT_LOOP = "dsh-minimal";

    /** 内置 Loop 名，按推荐了解顺序排列。 */
    public static final List<String> NAMES =
            List.of("dsh-minimal", "react", "claude-code", "codex", "plan-execute", "reflexion");

    private BuiltinLoops() {
    }

    /** 把全部内置 Loop 注册进给定注册表。 */
    public static void registerAll(LoopRegistry registry) {
        register(registry, new DshMinimalLoop());
        register(registry, new ReActLoop());
        register(registry, new ClaudeCodeLoop());
        register(registry, new CodexLoop());
        register(registry, new PlanExecuteLoop());
        register(registry, new ReflexionLoop());
        if (registry.defaultName() == null) {
            registry.setDefault(DEFAULT_LOOP);
        }
    }

    /** 只装了内置 Loop 的独立注册表，供脱离 Spring 的场景直接使用。 */
    public static LoopRegistry defaultRegistry() {
        LoopRegistry registry = new LoopRegistry();
        registerAll(registry);
        return registry;
    }

    /** 按注解名注册单个 Loop。 */
    public static void register(LoopRegistry registry, AgentLoop loop) {
        if (registry == null || loop == null) {
            return;
        }
        LlmLoop annotation = loop.getClass().getAnnotation(LlmLoop.class);
        String name = annotation != null && !annotation.value().isBlank() ? annotation.value() : loop.name();
        registry.register(name, loop);
    }
}