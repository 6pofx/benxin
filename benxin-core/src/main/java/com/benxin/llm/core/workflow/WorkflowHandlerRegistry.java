package com.benxin.llm.core.workflow;

import com.benxin.llm.core.annotation.LlmNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Java 节点处理器的注册表。
 *
 * <p>这张表就是<b>信任边界本身</b>：工作流定义只能点名这里已有的处理器，
 * 于是"配置文件能不能执行任意代码"这个问题被收敛成了"宿主装了哪些处理器"。
 * 它也是工作流定义里 {@code handler:} 那个字符串唯一的解析依据。</p>
 *
 * <p>与 {@code LoopRegistry} / {@code ToolRegistry} 一样，注册表本身刻意做得很薄：
 * 名字 → 实例，顺序保留（便于错误提示里按注册顺序列出来），没有生命周期、没有懒加载。
 * Spring 侧由自动配置负责把 {@link LlmNode} 标注的 bean 收进来；
 * 脱离 Spring 时直接 {@code register(...)} 即可。</p>
 */
public final class WorkflowHandlerRegistry {

    private final Map<String, WorkflowHandler> handlers = new LinkedHashMap<>();

    public static WorkflowHandlerRegistry empty() {
        return new WorkflowHandlerRegistry();
    }

    public static WorkflowHandlerRegistry of(Map<String, WorkflowHandler> handlers) {
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry();
        if (handlers != null) {
            handlers.forEach(registry::register);
        }
        return registry;
    }

    /** 按名字登记；同名的后登记者覆盖先前的（与 {@code LoopRegistry} 一致）。 */
    public WorkflowHandlerRegistry register(String name, WorkflowHandler handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工作流处理器名字不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("工作流处理器 [" + name + "] 不能为 null");
        }
        handlers.put(name.trim(), handler);
        return this;
    }

    /**
     * 按 {@link LlmNode} 注解上的名字登记。
     *
     * <p>没有注解就直接报错，而不是回落到类名：类名会随着重构变化，
     * 而工作流定义里写的是字符串 —— 静默用类名等于埋一个只在重命名时爆的雷。</p>
     */
    public WorkflowHandlerRegistry register(WorkflowHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("工作流处理器不能为 null");
        }
        LlmNode annotation = handler.getClass().getAnnotation(LlmNode.class);
        if (annotation == null || annotation.value().isBlank()) {
            throw new IllegalArgumentException("工作流处理器 [" + handler.getClass().getName()
                    + "] 缺少 @LlmNode(\"名字\")，无法确定它在工作流里叫什么");
        }
        return register(annotation.value(), handler);
    }

    public boolean contains(String name) {
        return name != null && handlers.containsKey(name.trim());
    }

    public Optional<WorkflowHandler> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(handlers.get(name.trim()));
    }

    /**
     * 取一个处理器，取不到就抛一句能直接照做的话。
     *
     * <p>把"已注册了哪些"写进消息里：工作流跑挂时，最有用的信息往往是
     * "你是不是把名字写错了"，而不是一句干巴巴的 not found。</p>
     */
    public WorkflowHandler require(String name) {
        WorkflowHandler handler = name == null ? null : handlers.get(name.trim());
        if (handler == null) {
            throw new IllegalArgumentException("未注册的工作流处理器 [" + name + "]，已注册: " + names());
        }
        return handler;
    }

    /** 已注册的名字，按登记顺序；返回不可变集合。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    public int size() {
        return handlers.size();
    }

    @Override
    public String toString() {
        return "WorkflowHandlers" + handlers.keySet();
    }
}
