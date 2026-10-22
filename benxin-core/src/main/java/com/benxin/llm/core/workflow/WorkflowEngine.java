package com.benxin.llm.core.workflow;

import com.benxin.llm.core.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流执行引擎：按图推进节点，直到走到 END、走出死胡同、或耗尽步数预算。
 *
 * <p><b>为什么单独做成一个类，而不是塞进 {@code WorkflowLoop}？</b>
 * 因为"编排"与"接入 Agent"是两个变化速度完全不同的东西。引擎只依赖
 * {@link WorkflowRuntime} 这个四方法的接口，于是它的全部行为（分支选择、循环、重试、
 * 预算中断、死循环护栏）都能用一个假运行时精确断言；而接入层可以随框架演进而改，
 * 不必重新验证编排逻辑。</p>
 *
 * <p><b>三道防线，针对三种不同的失控：</b></p>
 * <ol>
 *   <li><b>步数预算</b>（{@link WorkflowRuntime#outOfBudget()}）：拦住"模型一直在调工具"，
 *       因为只有调模型才消耗步数。</li>
 *   <li><b>单节点访问上限</b>（{@link WorkflowDefinition#maxVisitsPerNode()}）：
 *       拦住"不调模型的死循环"，例如 branch ↔ set 互指 —— 这类环一个 token 都不烧，
 *       却能把 CPU 跑满，预算机制对它完全无效。</li>
 *   <li><b>节点级重试 + continueOnError</b>：把"某个工具偶发失败"与"整张图崩掉"区分开。</li>
 * </ol>
 *
 * <p>引擎本身<b>无状态</b>，可以安全共享；一次运行的全部状态都在
 * {@link WorkflowState} 里，因此同一个引擎实例可以并发跑多张图。</p>
 */
public final class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 事件负载里输出文本的截断长度：事件是给人看的，不该把整篇报告塞进去。 */
    private static final int EVENT_TEXT_LIMIT = 512;

    /**
     * 跑完一张图。
     *
     * @param definition 工作流定义（已在构建期校验过）
     * @param state      黑板，调用方负责放入初始 {@code input}
     * @param runtime    副作用出口
     * @return 本次运行的产物
     * @throws WorkflowException 节点失败且未开启 {@code continueOnError}，或触发死循环护栏
     */
    public WorkflowRun run(WorkflowDefinition definition, WorkflowState state, WorkflowRuntime runtime) {
        WorkflowNode node = definition.resolveEntry();
        List<String> visited = new ArrayList<>();
        Map<String, Integer> visits = new LinkedHashMap<>();
        int executions = 0;
        String finalOutput = "";

        runtime.emit("workflow.start", event(
                "workflow", definition.name(),
                "entry", node.id(),
                "nodes", definition.nodes().size()));

        while (node != null) {
            int already = visits.getOrDefault(node.id(), 0);
            if (already >= definition.maxVisitsPerNode()) {
                // 先判护栏再计数：否则事件里的 visits 会比访问表多一次，两处口径对不上，
                // 排查"到底转了几圈"时反而要多绕一层。
                runtime.emit("workflow.loop-guard", event("workflow", definition.name(), "node", node.id(),
                        "visits", already));
                throw new WorkflowException(definition.name(), node.id(),
                        "节点 [" + node.id() + "] 已被访问 " + already + " 次，已达上限 "
                                + definition.maxVisitsPerNode() + "，判定为死循环。"
                                + "请检查出边条件是否存在永远成立的环，或调大 maxVisitsPerNode。");
            }
            int seen = visits.merge(node.id(), 1, Integer::sum);
            if (runtime.outOfBudget()) {
                log.debug("[workflow:{}] 步数预算耗尽，停在节点 [{}]", definition.name(), node.id());
                runtime.emit("workflow.truncated", event("workflow", definition.name(), "node", node.id(),
                        "executions", executions));
                return new WorkflowRun(definition.name(), finalOutput.isEmpty() ? state.last() : finalOutput,
                        visited, executions, true, runtime.truncatedNodes());
            }

            visited.add(node.id());
            executions++;
            Outcome outcome;
            try {
                outcome = execute(definition, node, state, runtime);
            } catch (RuntimeException e) {
                // 硬失败的节点同样要进轨迹：审计一份"失败了"的运行，最需要的恰恰是
                // "它试图走哪里"。以前 workflow.node 只在执行成功之后广播，于是轨迹
                // 只能证明"走过哪些成功的节点"，失败节点只剩事件表里的一个名字。
                runtime.emit("workflow.node", event(
                        "workflow", definition.name(),
                        "node", node.id(),
                        "type", node.type().wireName(),
                        "executions", executions,
                        "failed", true,
                        "output", null));
                throw e;
            }
            if (!outcome.failureSwallowed()) {
                // 节点成功了才清空失败原因。刻意不在节点开始前清 ——
                // 否则补偿分支刚读到 ${error}，"下一节点启动"就把它擦掉，
                // 于是"把失败原因写进最终答复"这个最有用的补偿写法会失效。
                state.clearError();
            }
            if (!outcome.variables().isEmpty()) {
                // 先写变量、再记节点输出：节点 id 那个键永远代表它自己的输出，
                // 处理器返回同名变量不会意外把它顶掉（见 NodeOutput 的键冲突规则）。
                state.putAll(outcome.variables());
            }
            if (outcome.producesOutput()) {
                state.recordNodeOutput(node.id(), outcome.output());
            }
            runtime.emit("workflow.node", event(
                    "workflow", definition.name(),
                    "node", node.id(),
                    "type", node.type().wireName(),
                    "executions", executions,
                    "output", abbreviate(outcome.output())));

            if (node.type() == NodeType.END) {
                finalOutput = outcome.output() == null ? "" : outcome.output();
                break;
            }

            List<WorkflowEdge> edges = definition.outgoing(node.id());
            if (edges.isEmpty()) {
                finalOutput = state.last();
                runtime.emit("workflow.dead-end", event("workflow", definition.name(), "node", node.id(),
                        "reason", "没有出边"));
                break;
            }
            WorkflowEdge chosen = select(definition, node, edges, state, runtime);
            if (chosen == null) {
                finalOutput = state.last();
                runtime.emit("workflow.dead-end", event("workflow", definition.name(), "node", node.id(),
                        "reason", "没有条件成立的出边"));
                if (node.type() == NodeType.BRANCH) {
                    // 分支节点走到死胡同几乎总是漏写了默认边，值得单独提醒一句
                    log.warn("[workflow:{}] 分支节点 [{}] 没有任何条件成立且缺少默认边，工作流在此结束；"
                                    + "若非本意，请补一条不带 when 的边作为默认分支",
                            definition.name(), node.id());
                }
                break;
            }
            node = definition.require(chosen.to());
        }

        WorkflowRun run = new WorkflowRun(definition.name(), finalOutput, visited, executions, false,
                runtime.truncatedNodes());
        runtime.emit("workflow.end", event("workflow", definition.name(), "executions", executions,
                "visited", run.visited(), "output", abbreviate(finalOutput),
                "truncatedNodes", run.truncatedNodes()));
        return run;
    }

    // ------------------------------------------------------------------
    // 条件选边
    // ------------------------------------------------------------------

    /**
     * 选出下一条边：按声明顺序取第一条条件成立的。
     *
     * <p>顺序即优先级 —— 这个决定让"默认分支放最后"成为唯一需要记住的规则，
     * 比引入 {@code priority} 数字更好维护。</p>
     *
     * <p>条件可能是用户写的 lambda：它在求值时抛异常必须能定位到"哪条边"，
     * 否则会变成一条指向未知位置的 NPE。</p>
     */
    private WorkflowEdge select(WorkflowDefinition definition, WorkflowNode node,
                                List<WorkflowEdge> edges, WorkflowState state,
                                WorkflowRuntime runtime) {
        for (WorkflowEdge edge : edges) {
            boolean matched;
            try {
                matched = edge.matches(state);
            } catch (RuntimeException e) {
                runtime.emit("workflow.edge-error", event("workflow", definition.name(),
                        "node", node.id(), "edge", edge.toString(),
                        "error", String.valueOf(e.getMessage())));
                throw new WorkflowException(definition.name(), node.id(),
                        "节点 [" + node.id() + "] 的出边 " + edge + " 条件求值失败：" + e, e);
            }
            if (matched) {
                return edge;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 节点执行
    // ------------------------------------------------------------------

    /**
     * 一次节点执行的产物。
     *
     * @param variables 要写回黑板的变量；Java 节点是唯一会带变量的类型，
     *                  其余节点返回空表。写入时机由引擎统一掌握（见 {@code run}）。
     */
    private record Outcome(String output, boolean producesOutput, boolean failureSwallowed,
                           Map<String, Object> variables) {

        static Outcome of(String output) {
            return new Outcome(output, true, false, Map.of());
        }

        static Outcome of(String output, Map<String, Object> variables) {
            return new Outcome(output, true, false, variables == null ? Map.of() : variables);
        }

        /** 纯控制节点（branch / set）不产生输出，因此也不覆盖黑板的 {@code last}。 */
        static Outcome silent() {
            return new Outcome(null, false, false, Map.of());
        }

        /** 同上，但带回变量（Java 节点返回 {@code NodeOutput.none()} 时走这条）。 */
        static Outcome silent(Map<String, Object> variables) {
            return new Outcome(null, false, false, variables == null ? Map.of() : variables);
        }

        /** 失败了但开启了 continueOnError：不中断，但仍要把 {@code error} 留给下游读。 */
        static Outcome swallowed() {
            return new Outcome("", true, true, Map.of());
        }
    }

    /** 带重试的节点执行：重试次数来自节点自身的 {@code retry}。 */
    private Outcome execute(WorkflowDefinition definition, WorkflowNode node, WorkflowState state,
                            WorkflowRuntime runtime) {
        int attempts = node.retry() + 1;
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return dispatch(definition, node, state, runtime);
            } catch (RuntimeException e) {
                failure = e;
                if (attempt < attempts) {
                    log.warn("[workflow:{}] 节点 [{}] 第 {} 次执行失败（{}/{}），稍后重试：{}",
                            definition.name(), node.id(), attempt, attempt, attempts, e.toString());
                    runtime.emit("workflow.node-retry", event("workflow", definition.name(), "node", node.id(),
                            "attempt", attempt, "error", String.valueOf(e.getMessage())));
                }
            }
        }

        String message = "节点 [" + node.id() + "]（" + node.type().wireName() + "）执行失败："
                + (failure == null ? "未知原因" : String.valueOf(failure.getMessage()));
        state.markError(message);
        // 事件里下发的是同一份 message，而不是裸异常消息：
        // 同一个失败会出现在四个可观测面上 —— 事件表、访问表（error 列）、${error} 提示词、
        // 以及出边的条件表达式。它们说法不一致时，排查的人只看一边就会被误导，
        // 因此统一成"含节点 id 与类型"的那一份（裸消息是它的子串，按原文匹配的调用方不受影响）。
        runtime.emit("workflow.node-error", event("workflow", definition.name(), "node", node.id(),
                "error", message,
                "continueOnError", node.continueOnError()));
        if (node.continueOnError()) {
            // 不抛异常，让出边的条件有机会读 ${error} 做补偿分支
            log.warn("[workflow:{}] 节点 [{}] 失败但已开启 continueOnError，继续沿出边推进",
                    definition.name(), node.id());
            return Outcome.swallowed();
        }
        throw new WorkflowException(definition.name(), node.id(), message, failure);
    }

    private Outcome dispatch(WorkflowDefinition definition, WorkflowNode node, WorkflowState state,
                             WorkflowRuntime runtime) {
        return switch (node.type()) {
            case START -> {
                String text = node.prompt() == null || node.prompt().isBlank()
                        ? state.input()
                        : state.render(node.prompt());
                yield Outcome.of(text);
            }
            case AGENT -> {
                String system = state.render(node.system());
                String prompt = compose(node, state);
                warnUnresolved(node, prompt, system, runtime);
                yield Outcome.of(runtime.callAgent(system, prompt, node));
            }
            case TOOL -> {
                Map<String, Object> args = Templates.renderArgs(node.args(), state.lookup());
                yield Outcome.of(runtime.callTool(node.tool(), args, node));
            }
            case JAVA -> toOutcome(runtime.callHandler(node.handler(), nodeInput(node, state), node));
            case CODE -> toOutcome(node.behavior().handle(nodeInput(node, state)));
            case SET -> {
                node.set().forEach((key, template) -> state.put(key, state.render(template)));
                yield Outcome.silent();
            }
            case BRANCH -> Outcome.silent();
            case END -> {
                String text = node.output() == null || node.output().isBlank()
                        ? state.last()
                        : state.render(node.output());
                yield Outcome.of(text);
            }
        };
    }

    /**
     * 构造交给节点行为的输入：参数已渲染，黑板是只读快照。
     *
     * <p>{@code java} 节点与 {@code code} 节点共用这一份，因此两种写法的节点
     * 看到的输入完全一致 —— 迁移时只需要换个绑定方式。</p>
     */
    private NodeInput nodeInput(WorkflowNode node, WorkflowState state) {
        Map<String, Object> args = Templates.renderArgs(node.args(), state.lookup());
        return new NodeInput(node.id(), args, state.variables(),
                state.input(), state.last(), state.error());
    }

    /** 把行为返回的 {@link NodeOutput} 转成引擎内部的 {@link Outcome}。 */
    private static Outcome toOutcome(NodeOutput result) {
        if (result == null) {
            // 接口约定不得返回 null；这里兜成"无输出"，比让引擎在下一行 NPE 清楚得多
            return Outcome.silent();
        }
        return result.producesOutput()
                ? Outcome.of(result.output(), result.variables())
                : Outcome.silent(result.variables());
    }

    /** 把 {@code instruction} 与 {@code prompt} 渲染并拼成最终提示词。 */
    /** 渲染后仍残留的占位符，用于把"拼错的变量名"报出来。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * 提示词里还有没被解析的 {@code ${...}}：变量名拼错了，或这个事实从没被写进黑板。
     *
     * <p>隔离节点尤其需要这条警告 —— 它没有历史可兜底，模板里少一个事实，
     * 模型就真的只能凭空编。保留原文（而不是替换成空串）只是让它"看得见"，
     * 还得把"看见了什么"报出来。</p>
     */
    private void warnUnresolved(WorkflowNode node, String prompt, String system, WorkflowRuntime runtime) {
        List<String> names = new ArrayList<>();
        collectUnresolved(prompt, names);
        collectUnresolved(system, names);
        if (!names.isEmpty()) {
            runtime.emit("workflow.unresolved-variable", Map.of("node", node.id(), "names", names));
        }
    }

    private static void collectUnresolved(String text, List<String> into) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty() && !into.contains(name)) {
                into.add(name);
            }
        }
    }

    private String compose(WorkflowNode node, WorkflowState state) {        String prompt = state.render(node.prompt());
        if (node.instruction() == null || node.instruction().isBlank()) {
            return prompt;
        }
        return state.render(node.instruction()) + "\n\n" + prompt;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String abbreviate(String text) {
        return text == null ? null : Json.abbreviate(text.substring(0, Math.min(text.length(), EVENT_TEXT_LIMIT)));
    }

    /** 构造允许 null 值的事件负载（{@code Map.of} 不接受 null，故统一用 LinkedHashMap）。 */
    private static Map<String, Object> event(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            payload.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return payload;
    }
}
