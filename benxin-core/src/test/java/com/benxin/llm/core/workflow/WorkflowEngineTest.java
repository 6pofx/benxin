package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 工作流引擎：分支、循环、重试、预算与护栏。
 *
 * <p>全部用假运行时验证 —— 编排出错的地方（走错边、绕不出来、失败没兜住）恰恰是最该被
 * 反复验证的地方，而这些验证不需要模型、不需要网络，也不该需要。</p>
 */
class WorkflowEngineTest {

    // ------------------------------------------------------------------
    // 假运行时：按节点 id 编排"会返回什么、第几次失败"
    // ------------------------------------------------------------------

    private static final class FakeRuntime implements WorkflowRuntime {

        private final Map<String, String> outputs = new LinkedHashMap<>();
        private final Map<String, List<String>> sequences = new LinkedHashMap<>();
        private final Map<String, Integer> failTimes = new LinkedHashMap<>();
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private final Map<String, String> prompts = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> toolArgs = new LinkedHashMap<>();
        private final List<String> agentNodes = new ArrayList<>();
        private final List<String> toolNames = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        private int budgetLimit = Integer.MAX_VALUE;
        private int calls;

        FakeRuntime output(String nodeId, String text) {
            outputs.put(nodeId, text);
            return this;
        }

        /** 让某节点按次序返回不同内容（用于验证循环真的转起来了）。 */
        FakeRuntime sequence(String nodeId, String... values) {
            sequences.put(nodeId, List.of(values));
            return this;
        }

        /** 让某节点的前 {@code times} 次执行都抛异常。 */
        FakeRuntime failFirst(String nodeId, int times) {
            failTimes.put(nodeId, times);
            return this;
        }

        /** 运行时调用满 {@code limit} 次之后报告预算耗尽。 */
        FakeRuntime budget(int limit) {
            this.budgetLimit = limit;
            return this;
        }

        int attempts(String nodeId) {
            return attempts.getOrDefault(nodeId, 0);
        }

        String prompt(String nodeId) {
            return prompts.get(nodeId);
        }

        Map<String, Object> toolArgs(String tool) {
            return toolArgs.get(tool);
        }

        @Override
        public String callAgent(String prompt, WorkflowNode node) {
            calls++;
            int attempt = begin(node.id());
            agentNodes.add(node.id());
            prompts.put(node.id(), prompt);
            List<String> sequence = sequences.get(node.id());
            if (sequence != null && !sequence.isEmpty()) {
                return sequence.get(Math.min(attempt - 1, sequence.size() - 1));
            }
            return outputs.getOrDefault(node.id(), "out-" + node.id());
        }

        @Override
        public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
            calls++;
            begin(node.id());
            toolNames.add(tool);
            toolArgs.put(tool, args);
            return outputs.getOrDefault(node.id(), "tool-" + tool);
        }

        @Override
        public void emit(String type, Object payload) {
            events.add(type);
        }

        @Override
        public boolean outOfBudget() {
            return calls >= budgetLimit;
        }

        /** 计数并决定这次是否失败；返回本次是第几次执行（从 1 开始）。 */
        private int begin(String nodeId) {
            int attempt = attempts.merge(nodeId, 1, Integer::sum);
            Integer limit = failTimes.get(nodeId);
            if (limit != null && attempt <= limit) {
                throw new IllegalStateException("模拟失败:" + nodeId);
            }
            return attempt;
        }
    }

    private static WorkflowRun run(WorkflowDefinition definition, FakeRuntime runtime, String input) {
        return new WorkflowEngine().run(definition, new WorkflowState(input), runtime);
    }

    private static WorkflowNode agent(String id, String prompt) {
        return WorkflowNode.builder(id).type(NodeType.AGENT).prompt(prompt).build();
    }

    private static WorkflowNode end(String id, String output) {
        return WorkflowNode.builder(id).type(NodeType.END).output(output).build();
    }

    private static WorkflowNode branch(String id) {
        return WorkflowNode.builder(id).type(NodeType.BRANCH).build();
    }

    // ------------------------------------------------------------------
    // 顺序与收尾
    // ------------------------------------------------------------------

    @Test
    @DisplayName("顺序推进：END 节点渲染的输出就是最终结果")
    void runsSequentially() {
        WorkflowDefinition definition = WorkflowDefinition.builder("seq")
                .node(agent("a", "第一步"))
                .node(agent("b", "第二步：${a}"))
                .node(end("done", "结果=${a}|${b}"))
                .edge("a", "b")
                .edge("b", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "甲").output("b", "乙");
        WorkflowRun result = run(definition, runtime, "任务");

        assertThat(result.output()).isEqualTo("结果=甲|乙");
        assertThat(result.visited()).containsExactly("a", "b", "done");
        assertThat(result.executions()).isEqualTo(3);
        assertThat(result.completed()).isTrue();
        // 模板插值确实把上一个节点的输出带给了下一个节点
        assertThat(runtime.prompt("b")).isEqualTo("第二步：甲");
    }

    @Test
    @DisplayName("instruction 会作为前缀拼在 prompt 之前")
    void prependsInstruction() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT)
                        .instruction("你是严格的评审者")
                        .prompt("看看 ${input}")
                        .build())
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime();
        run(definition, runtime, "这段代码");

        assertThat(runtime.prompt("a")).isEqualTo("你是严格的评审者\n\n看看 这段代码");
    }

    @Test
    @DisplayName("START 节点没写 prompt 时透传任务原文")
    void startPassesThroughInput() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(end("done", "拿到=${begin}"))
                .edge("begin", "done")
                .build();

        WorkflowRun result = run(definition, new FakeRuntime(), "原始任务");

        assertThat(result.output()).isEqualTo("拿到=原始任务");
    }

    @Test
    @DisplayName("END 节点没写 output 时回退到最后一个输出")
    void endFallsBackToLastOutput() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "干活"))
                .node(WorkflowNode.builder("done").type(NodeType.END).build())
                .edge("a", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "干完了");
        assertThat(run(definition, runtime, "x").output()).isEqualTo("干完了");
    }

    // ------------------------------------------------------------------
    // 分支
    // ------------------------------------------------------------------

    @Test
    @DisplayName("条件成立走条件边，不成立落到默认边")
    void branchesByCondition() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("analyze", "分析"))
                .node(branch("route"))
                .node(end("serious", "严重路径"))
                .node(end("mild", "普通路径"))
                .edge("analyze", "route")
                .edge("route", "serious", "${analyze} contains 严重")
                .edge("route", "mild")
                .build();

        FakeRuntime serious = new FakeRuntime().output("analyze", "发现严重问题");
        assertThat(run(definition, serious, "x").output()).isEqualTo("严重路径");

        FakeRuntime mild = new FakeRuntime().output("analyze", "没什么问题");
        assertThat(run(definition, mild, "x").output()).isEqualTo("普通路径");
    }

    @Test
    @DisplayName("出边按声明顺序取第一条成立的 —— 顺序即优先级")
    void respectsEdgeOrder() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "写"))
                .node(branch("route"))
                .node(end("first", "第一"))
                .node(end("second", "第二"))
                .edge("a", "route")
                // 两条都成立，必须走先声明的那条
                .edge("route", "first", "${a} is not empty")
                .edge("route", "second", "${a} is not empty")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "内容");
        assertThat(run(definition, runtime, "x").output()).isEqualTo("第一");
    }

    @Test
    @DisplayName("分支节点没有任何边成立时按死胡同收尾，并广播 dead-end")
    void branchDeadEnd() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "写"))
                .node(branch("route"))
                .node(end("never", "不会到"))
                .edge("a", "route")
                .edge("route", "never", "${a} contains 永远不会出现的词")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "内容");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("内容");
        assertThat(result.visited()).containsExactly("a", "route");
        assertThat(runtime.events).contains("workflow.dead-end");
    }

    // ------------------------------------------------------------------
    // set 与 tool 节点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("set 节点写变量，后续节点能读到")
    void setNodeWritesVariables() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("init").type(NodeType.SET)
                        .set(Map.of("phase", "起步", "note", "基于 ${input}"))
                        .build())
                .node(end("done", "阶段=${phase} 备注=${note}"))
                .edge("init", "done")
                .build();

        WorkflowRun result = run(definition, new FakeRuntime(), "任务A");

        assertThat(result.output()).isEqualTo("阶段=起步 备注=基于 任务A");
    }

    @Test
    @DisplayName("tool 节点直接调工具，参数模板已渲染")
    void toolNodeRendersArgs() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "产出关键词"))
                .node(WorkflowNode.builder("search").type(NodeType.TOOL)
                        .tool("search")
                        .args(Map.of("keyword", "${a}", "limit", 2))
                        .build())
                .node(end("done", "搜索结果：${search}"))
                .edge("a", "search")
                .edge("search", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "内存泄漏").output("search", "命中 2 条");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(runtime.toolNames).containsExactly("search");
        assertThat(runtime.toolArgs("search"))
                .containsEntry("keyword", "内存泄漏")
                .containsEntry("limit", 2);
        assertThat(result.output()).isEqualTo("搜索结果：命中 2 条");
    }

    // ------------------------------------------------------------------
    // 循环与护栏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回边构成循环，条件不再成立时正常退出")
    void loopsUntilConditionFails() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("work", "继续干活"))
                .node(branch("check"))
                .node(end("done", "收工"))
                .edge("work", "check")
                .edge("check", "work", "${work} contains 继续")
                .edge("check", "done")
                .build();

        // 第一次说"继续做"，第二次说"停" —— 于是循环转一圈后正常退出
        FakeRuntime runtime = new FakeRuntime().sequence("work", "继续做", "停");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.visited()).containsExactly("work", "check", "work", "check", "done");
        assertThat(runtime.attempts("work")).isEqualTo(2);
        assertThat(result.output()).isEqualTo("收工");
    }

    @Test
    @DisplayName("不调模型的死循环由单节点访问上限拦住（步数预算对它无效）")
    void guardsModelFreeInfiniteLoop() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(branch("a"))
                .node(branch("b"))
                .edge("a", "b")
                .edge("b", "a")
                .maxVisitsPerNode(3)
                .build();

        FakeRuntime runtime = new FakeRuntime();
        WorkflowException error = catchThrowableOfType(
                () -> run(definition, runtime, "x"), WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("死循环").contains("maxVisitsPerNode");
        assertThat(error.nodeId()).isEqualTo("a");
        // 全程没有一次模型调用 —— 这正是"步数预算拦不住它"的证明
        assertThat(runtime.agentNodes).isEmpty();
    }

    // ------------------------------------------------------------------
    // 失败处理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("retry：前几次失败后重试成功，并广播 node-retry")
    void retriesThenSucceeds() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("干活").retry(2).build())
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "终于成功").failFirst("a", 2);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("终于成功");
        assertThat(runtime.attempts("a")).isEqualTo(3);
        assertThat(runtime.events).contains("workflow.node-retry");
    }

    @Test
    @DisplayName("重试用尽后失败，异常里带上节点 id")
    void failsAfterRetriesExhausted() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("干活").retry(1).build())
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().failFirst("a", 99);
        WorkflowException error = catchThrowableOfType(
                () -> run(definition, runtime, "x"), WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.nodeId()).isEqualTo("a");
        assertThat(error.getMessage()).contains("[a]").contains("模拟失败");
    }

    @Test
    @DisplayName("continueOnError：节点失败不中断，出边可读 ${error} 走补偿分支")
    void continueOnErrorAllowsCompensation() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("干活")
                        .continueOnError(true).build())
                .node(end("recover", "补偿：${error}"))
                .node(end("normal", "正常：${a}"))
                .edge("a", "recover", "${error} is not empty")
                .edge("a", "normal")
                .build();

        FakeRuntime runtime = new FakeRuntime().failFirst("a", 99);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).startsWith("补偿：").contains("[a]");
        assertThat(runtime.events).contains("workflow.node-error");
    }

    @Test
    @DisplayName("后续节点成功后 error 被清空，不会把上一次的失败带进条件")
    void clearsErrorAfterSuccess() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("注定失败")
                        .continueOnError(true).build())
                .node(agent("b", "正常干活"))
                .node(branch("route"))
                .node(end("recover", "补偿"))
                .node(end("normal", "正常"))
                .edge("a", "b")
                .edge("b", "route")
                .edge("route", "recover", "${error} is not empty")
                .edge("route", "normal")
                .build();

        // a 失败但 continueOnError；b 成功，于是走到 route 时 error 必须已被清空
        FakeRuntime runtime = new FakeRuntime().failFirst("a", 99);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("正常");
    }

    // ------------------------------------------------------------------
    // 预算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("预算耗尽：如实标记 truncated，不假装完成")
    void truncatesWhenOutOfBudget() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(agent("b", "二"))
                .node(agent("c", "三"))
                .node(end("done", "${c}"))
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().output("a", "甲").output("b", "乙").output("c", "丙")
                .budget(2);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.truncated()).isTrue();
        assertThat(result.completed()).isFalse();
        assertThat(result.output()).isEqualTo("乙");
        assertThat(result.visited()).containsExactly("a", "b");
        assertThat(runtime.events).contains("workflow.truncated");
    }

    @Test
    @DisplayName("预算一开始就不够时不执行任何节点")
    void zeroBudgetExecutesNothing() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().budget(0);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.truncated()).isTrue();
        assertThat(result.executions()).isZero();
        assertThat(runtime.agentNodes).isEmpty();
    }

    // ------------------------------------------------------------------
    // 事件与黑板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("事件顺序：start → 每个节点 → end")
    void emitsEventsInOrder() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime();
        run(definition, runtime, "x");

        assertThat(runtime.events).containsExactly(
                "workflow.start", "workflow.node", "workflow.node", "workflow.end");
    }

    @Test
    @DisplayName("黑板保留全部节点输出，最后一个输出同时是 last")
    void blackboardKeepsEveryOutput() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(agent("b", "二"))
                .node(end("done", "${last}"))
                .edge("a", "b")
                .edge("b", "done")
                .build();

        WorkflowState state = new WorkflowState("任务");
        WorkflowRun result = new WorkflowEngine().run(definition, state, new FakeRuntime());

        assertThat(result.output()).isEqualTo("out-b");
        assertThat(state.variables())
                .containsEntry("a", "out-a")
                .containsEntry("b", "out-b");
        // 保留名不在变量表里，但模板与快照都能取到
        assertThat(state.snapshot()).containsEntry("input", "任务");
        assertThat(state.last()).isEqualTo("out-b");
    }

    @Test
    @DisplayName("branch 与 set 不覆盖 last —— 它们不产生输出")
    void controlNodesDoNotTouchLast() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "真正的内容"))
                .node(branch("route"))
                .node(WorkflowNode.builder("mark").type(NodeType.SET).set(Map.of("k", "v")).build())
                .node(end("done", "${last}"))
                .edge("a", "route")
                .edge("route", "mark")
                .edge("mark", "done")
                .build();

        // FakeRuntime 的默认输出是 "out-<节点 id>"，这里显式给出内容
        WorkflowRun result = run(definition, new FakeRuntime().output("a", "真正的内容"), "x");
        assertThat(result.output()).isEqualTo("真正的内容");
    }

    @Test
    @DisplayName("引擎无状态，可被复用")
    void engineIsReusable() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        WorkflowEngine engine = new WorkflowEngine();
        WorkflowRun first = engine.run(definition, new WorkflowState("x"), new FakeRuntime());
        WorkflowRun second = engine.run(definition, new WorkflowState("y"), new FakeRuntime());

        assertThat(first.output()).isEqualTo("out-a");
        assertThat(second.output()).isEqualTo("out-a");
    }
}
