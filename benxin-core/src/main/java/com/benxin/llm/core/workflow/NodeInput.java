package com.benxin.llm.core.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交给 {@link WorkflowHandler} 的只读输入。
 *
 * <p><b>为什么是快照而不是可变的黑板？</b> 因为"哪个节点改了哪个变量"必须能回答得出来。
 * 处理器拿到的 {@code variables} 是一份不可变副本，想看什么看什么；要改状态只能通过
 * {@link NodeOutput#variables()} 显式交回，由引擎在唯一的一处统一写入 ——
 * 于是一次运行里所有状态变更都发生在引擎手上，日志与事件才对得上号。</p>
 *
 * <p>{@code args} 已经是渲染过的值（{@code ${...}} 替换完毕），{@code variables} 则是
 * 黑板现状，键只包含<b>用户变量</b>与各节点的输出；{@code input} / {@code last} /
 * {@code error} 这些内置量<b>不在</b> {@code variables} 里，请用同名访问器读
 * （它们本来就不是"变量"，而是本次运行的固定上下文）。</p>
 */
public record NodeInput(String nodeId,
                        Map<String, Object> args,
                        Map<String, Object> variables,
                        String input,
                        String last,
                        String error) {

    public NodeInput {
        // 用 LinkedHashMap 手工包一层而不是 Map.copyOf：后者不接受 null 值，
        // 而 YAML 里一个没写值的参数、或模板解析出来的 null，都不该让节点直接崩掉。
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        input = input == null ? "" : input;
        last = last == null ? "" : last;
    }

    /** 取一个参数并转成字符串；不存在返回空串。 */
    public String arg(String name) {
        return Templates.stringify(args.get(name)) == null ? "" : Templates.stringify(args.get(name));
    }

    /** 取一个参数的原始值（可能是数字、布尔、List、Map）；不存在返回 {@code null}。 */
    public Object argRaw(String name) {
        return args.get(name);
    }

    /** 取一个黑板变量并转成字符串；不存在返回空串。 */
    public String variable(String name) {
        return Templates.stringify(variables.get(name)) == null ? "" : Templates.stringify(variables.get(name));
    }

    /** 取一个黑板变量的原始值；不存在返回 {@code null}。 */
    public Object variableRaw(String name) {
        return variables.get(name);
    }

    /** 是否有失败原因可读（配合 {@code continueOnError} 的补偿逻辑）。 */
    public boolean hasError() {
        return error != null && !error.isBlank();
    }
}
