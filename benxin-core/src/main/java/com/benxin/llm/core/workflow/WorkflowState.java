package com.benxin.llm.core.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 工作流运行期的黑板：所有节点读写的共享变量都放在这里。
 *
 * <p>与 {@code LoopContext.attributes()} 的关系是"专用 vs 通用"：attributes 是框架级的
 * 公开抽屉，黑板是本模式自己的账本。两者在 {@code WorkflowLoop} 里对接一次，
 * 于是工作流内部完全不必知道 attributes 的存在。</p>
 *
 * <p><b>取值优先级：先查变量表，再查保留名。</b>保留名共五个 ——
 * {@code input}/{@code task}（本次任务原文）、{@code last}（上一个节点的输出）、
 * {@code error}（上一次节点失败的原因）、{@code current}（当前节点 id）。
 * 为避免"同名节点遮蔽保留名"这种最难查的 bug，节点 id 不允许与它们重名，
 * 由 {@link #RESERVED} 在加载期拦下并提示改名。</p>
 */
public final class WorkflowState {

    /** 保留名：既是模板里可用的内置变量，也是不允许用作节点 id 的名字。 */
    public static final Set<String> RESERVED = Set.of("input", "task", "last", "error", "current");

    private final String input;
    private final Map<String, Object> variables = new LinkedHashMap<>();

    private String last = "";
    private String error;
    private String current;

    public WorkflowState(String input) {
        this.input = input == null ? "" : input;
    }

    public String input() {
        return input;
    }

    public String last() {
        return last;
    }

    public String error() {
        return error;
    }

    public String current() {
        return current;
    }

    public Map<String, Object> variables() {
        return Collections.unmodifiableMap(variables);
    }

    /** 读取一个变量（不含保留名）。 */
    public Object variable(String name) {
        return variables.get(name);
    }

    /** 读取一个变量的字符串形式；不存在返回 {@code null}。 */
    public String text(String name) {
        return Templates.stringify(variables.get(name));
    }

    public boolean has(String name) {
        return variables.containsKey(name);
    }

    public void put(String name, Object value) {
        if (name != null && !name.isBlank()) {
            variables.put(name, value);
        }
    }

    public void putAll(Map<String, ?> values) {
        if (values != null) {
            values.forEach(this::put);
        }
    }

    /** 记录一个节点的输出，并把它设为"上一个输出"。 */
    public void recordNodeOutput(String nodeId, String output) {
        put(nodeId, output);
        this.last = output == null ? "" : output;
        this.current = nodeId;
    }

    /** 清空错误标记（节点成功时必须调，否则后续条件会读到陈旧的失败原因）。 */
    public void clearError() {
        this.error = null;
    }

    public void markError(String message) {
        this.error = message == null ? "" : message;
    }

    /**
     * 给 {@link Templates} / {@link Conditions} 用的取值函数。
     *
     * <p>先变量、后保留名；都取不到返回 {@code null}，由模板层决定"保留原文"。</p>
     */
    public Function<String, Object> lookup() {
        return key -> {
            if (key == null) {
                return null;
            }
            if (variables.containsKey(key)) {
                return variables.get(key);
            }
            return switch (key) {
                case "input", "task" -> input;
                case "last" -> last;
                case "error" -> error;
                case "current" -> current;
                default -> null;
            };
        };
    }

    /** 渲染一段模板。 */
    public String render(String template) {
        return Templates.render(template, lookup());
    }

    /** 把黑板导出成普通 Map，用于事件负载与 {@code LoopResult.attributes()}。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("input", input);
        snapshot.put("last", last);
        snapshot.put("current", current == null ? "" : current);
        if (error != null) {
            snapshot.put("error", error);
        }
        snapshot.putAll(variables);
        return snapshot;
    }
}
