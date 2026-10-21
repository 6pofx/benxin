package com.benxin.llm.core.workflow;

import com.benxin.llm.core.util.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link WorkflowHandler} 的产出。
 *
 * <p>分两条通道，而不是"返回 Object 再猜类型"：</p>
 * <ul>
 *   <li>{@link #output()} —— 节点的可见输出，写进黑板后可用 {@code ${节点 id}} 引用；</li>
 *   <li>{@link #variables()} —— 额外写回黑板的变量，供后续节点与出边条件按名字读。</li>
 * </ul>
 *
 * <p>结构化结果走变量通道，就不会被塞成一段 JSON 字符串再让下游去解析 ——
 * 那正是"Java 节点"相对 {@code tool} 节点最实际的那点差别。</p>
 *
 * <p><b>键冲突规则</b>：引擎先写变量、再写节点输出，因此<b>节点输出优先</b> ——
 * 处理器返回一个与自身节点 id 同名的变量不会生效（节点 id 这个名字永远代表它的输出）。
 * 另外节点 id 不能与 {@code input}/{@code last}/{@code error} 等内置量重名，这一点在加载期就已拦下。</p>
 */
public record NodeOutput(String output, Map<String, Object> variables) {

    public NodeOutput {
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** 只产出文本。 */
    public static NodeOutput of(String text) {
        return new NodeOutput(text == null ? "" : text, Map.of());
    }

    /** 只写变量；节点的文本输出取这些变量的 JSON 表示，便于直接 {@code ${节点 id}} 观察。 */
    public static NodeOutput vars(Map<String, Object> variables) {
        return new NodeOutput(Json.writeQuietly(variables == null ? Map.of() : variables), variables);
    }

    /** 文本 + 变量。 */
    public static NodeOutput of(String text, Map<String, Object> variables) {
        return new NodeOutput(text == null ? "" : text, variables);
    }

    /** 什么都不产出：像 {@code branch} / {@code set} 那样不覆盖黑板的 {@code last}。 */
    public static NodeOutput none() {
        return new NodeOutput(null, Map.of());
    }

    /** 是否有文本输出；{@link #none()} 为 {@code false}。 */
    public boolean producesOutput() {
        return output != null;
    }
}
