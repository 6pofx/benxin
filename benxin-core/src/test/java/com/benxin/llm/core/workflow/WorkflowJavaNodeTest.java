package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Java 节点在引擎里的行为：参数渲染、变量回写、失败语义、以及与既有节点的配合。
 *
 * <p>仍然用假运行时 —— 这批断言关心的是"引擎怎么对待 Java 节点的产出"，
 * 与真实的 {@code LoopContext} 无关，不该被它拖慢或绑住。</p>
 */
class WorkflowJavaNodeTest {

    /** 按节点 id 编排的假运行时；Java 节点真正转给注册表。 */
    private static final class FakeRuntime implements WorkflowRuntime {

        private final WorkflowHandlerRegistry handlers = new WorkflowHandlerRegistry();
        private final Map<String, String> agentOutputs = new LinkedHashMap<>();
        private final Map<String, String> toolOutputs = new LinkedHashMap<>();
        private final Map<String, NodeInput> seen = new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();

        private int budget = Integer.MAX_VALUE;
        private int calls;

        FakeRuntime handler(String name, WorkflowHandler handler) {
            handlers.register(name, handler);
            return this;
        }

        FakeRuntime agentOutput(String nodeId, String text) {
            agentOutputs.put(nodeId, text);
            return this;
        }

        FakeRuntime toolOutput(String nodeId, String text) {
            toolOutputs.put(nodeId, text);
            return this;
        }

        NodeInput seen(String nodeId) {
            return seen.get(nodeId);
        }

        @Override
        public String callAgent(String prompt, WorkflowNode node) {
            calls++;
            return agentOutputs.getOrDefault(node.id(), "out-" + node.id());
        }

        @Override
        public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
            calls++;
            return toolOutputs.getOrDefault(node.id(), "tool-" + tool);
        }

        @Override
        public NodeOutput callHandler(String handler, NodeInput input, WorkflowNode node) {
            calls++;
            seen.put(input.nodeId(), input);
            return handlers.require(handler).handle(input);
        }

        @Override
        public void emit(String type, Object payload) {
            events.add(type);
        }

        @Override
        public boolean outOfBudget() {
            return calls >= budget;
        }
    }

    private static WorkflowNode javaNode(String id, String handler, Map<String, Object> args) {
        return WorkflowNode.builder(id).type(NodeType.JAVA).handler(handler).args(args).build();
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

    private static WorkflowRun run(WorkflowDefinition definition, FakeRuntime runtime, String input) {
        return new WorkflowEngine().run(definition, new WorkflowState(input), runtime);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("args 会先渲染模板再交给处理器，输出与变量都写回黑板")
    void rendersArgsAndPublishesVariables() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(javaNode("calc", "sum", Map.of("a", "${input}", "b", "3")))
                .node(end("done", "out=${calc}|paired=${paired}"))
                .edge("calc", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().handler("sum",
                in -> NodeOutput.of("结果", Map.of("paired", in.arg("a") + "|" + in.arg("b"))));

        WorkflowRun result = run(definition, runtime, "任务");

        // ${input} 已被换成任务原文，数字保持原样
        assertThat(runtime.seen("calc").arg("a")).isEqualTo("任务");
        assertThat(runtime.seen("calc").arg("b")).isEqualTo("3");
        // 文本走 ${calc}，变量走 ${paired}
        assertThat(result.output()).isEqualTo("out=结果|paired=任务|3");
    }

    @Test
    @DisplayName("NodeOutput.none() 不覆盖黑板的 last（与 branch / set 同口径）")
    void noneKeepsLastOutput() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "干活"))
                .node(javaNode("silent", "noop", Map.of()))
                .node(end("done", "${last}"))
                .edge("a", "silent")
                .edge("silent", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime()
                .agentOutput("a", "甲")
                .handler("noop", in -> NodeOutput.none());

        assertThat(run(definition, runtime, "x").output()).isEqualTo("甲");
    }

    @Test
    @DisplayName("处理器能读到黑板快照，但改不动它 —— 状态变更只能经引擎")
    void handlerSeesReadOnlySnapshot() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "先产出"))
                .node(javaNode("inspect", "peek", Map.of()))
                .node(end("done", "${inspect}"))
                .edge("a", "inspect")
                .edge("inspect", "done")
                .build();

        AtomicReference<NodeInput> captured = new AtomicReference<>();
        FakeRuntime runtime = new FakeRuntime()
                .agentOutput("a", "甲")
                .handler("peek", in -> {
                    captured.set(in);
                    return NodeOutput.of("看过了");
                });

        run(definition, runtime, "任务原文");

        NodeInput input = captured.get();
        assertThat(input.nodeId()).isEqualTo("inspect");
        // 内置量走专门访问器，variables 里只有"用户变量 + 节点输出"
        assertThat(input.input()).isEqualTo("任务原文");
        assertThat(input.variables()).doesNotContainKey("input");
        assertThat(input.variable("a")).isEqualTo("甲");
        assertThat(input.last()).isEqualTo("甲");
        assertThat(input.hasError()).isFalse();
        assertThatThrownBy(() -> input.variables().put("偷写", "值"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("结构化结果走变量通道，类型保持为原始对象")
    void variablesKeepRawTypes() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(javaNode("produce", "make", Map.of()))
                .node(javaNode("consume", "take", Map.of()))
                .node(end("done", "${consume}"))
                .edge("produce", "consume")
                .edge("consume", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime()
                .handler("make", in -> NodeOutput.of("产出了 2 项",
                        Map.of("items", List.of("甲", "乙"))))
                .handler("take", in -> {
                    Object items = in.variableRaw("items");
                    boolean isList = items instanceof List<?> list && list.size() == 2;
                    return NodeOutput.of(isList ? "拿到列表" : "类型丢了");
                });

        assertThat(run(definition, runtime, "x").output()).isEqualTo("拿到列表");
    }

    @Test
    @DisplayName("节点输出优先于同名变量：节点 id 永远代表它自己的输出")
    void nodeOutputWinsOverSameNamedVariable() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(javaNode("calc", "hijack", Map.of()))
                .node(end("done", "${calc}|${other}"))
                .edge("calc", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().handler("hijack",
                in -> NodeOutput.of("真输出", Map.of("calc", "冒名顶替", "other", "保留")));

        assertThat(run(definition, runtime, "x").output()).isEqualTo("真输出|保留");
    }

    @Test
    @DisplayName("处理器抛异常时按节点的 retry 重试")
    void handlerFailureIsRetried() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("flaky").type(NodeType.JAVA).handler("flaky").retry(2).build())
                .node(end("done", "${flaky}"))
                .edge("flaky", "done")
                .build();

        AtomicInteger attempts = new AtomicInteger();
        FakeRuntime runtime = new FakeRuntime().handler("flaky", in -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("模拟处理器失败");
            }
            return NodeOutput.of("第三次成功");
        });

        WorkflowRun result = run(definition, runtime, "x");

        assertThat(attempts).hasValue(3);
        assertThat(result.output()).isEqualTo("第三次成功");
        assertThat(runtime.events).contains("workflow.node-retry");
    }

    @Test
    @DisplayName("continueOnError 时失败不中断，补偿分支能读到含节点 id 的 ${error}")
    void handlerFailureCanBeCompensated() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("boom").type(NodeType.JAVA).handler("boom")
                        .continueOnError(true).build())
                .node(end("recover", "补偿：${error}"))
                .node(end("normal", "正常：${boom}"))
                .edge("boom", "recover", "${error} is not empty")
                .edge("boom", "normal")
                .build();

        FakeRuntime runtime = new FakeRuntime().handler("boom", in -> {
            throw new IllegalStateException("业务规则不满足");
        });

        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).startsWith("补偿：")
                .contains("[boom]")
                .contains("业务规则不满足");
    }

    @Test
    @DisplayName("处理器没注册时失败信息要带上节点 id 与已注册清单")
    void missingHandlerFailsWithList() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(javaNode("calc", "not-there", Map.of()))
                .node(end("done", "${calc}"))
                .edge("calc", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().handler("registered", in -> NodeOutput.of("x"));

        WorkflowException error = catchThrowableOfType(
                () -> run(definition, runtime, "x"), WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.nodeId()).isEqualTo("calc");
        assertThat(error.getMessage())
                .contains("[calc]")
                .contains("未注册的工作流处理器 [not-there]")
                .contains("registered");
    }

    @Test
    @DisplayName("Java 节点可以当作循环体，退出条件读它写回的变量")
    void javaNodeWorksInsideLoop() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(javaNode("tick", "tick", Map.of()))
                .node(branch("check"))
                .node(end("done", "${tick}"))
                .edge("tick", "check")
                .edge("check", "tick", "${gate} contains 继续")
                .edge("check", "done")
                .build();

        AtomicInteger count = new AtomicInteger();
        FakeRuntime runtime = new FakeRuntime().handler("tick", in -> {
            int n = count.incrementAndGet();
            String gate = n < 3 ? "继续" : "停";
            return NodeOutput.of("第 " + n + " 圈", Map.of("gate", gate));
        });

        WorkflowRun result = run(definition, runtime, "x");

        assertThat(count).hasValue(3);
        assertThat(result.visited()).containsExactly(
                "tick", "check", "tick", "check", "tick", "check", "done");
        assertThat(result.output()).isEqualTo("第 3 圈");
    }

    @Test
    @DisplayName("相对其它节点，Java 节点不进模型历史、也不产生工具记录")
    void javaNodeIsInvisibleToModelAndTools() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "先想"))
                .node(javaNode("clean", "clean", Map.of()))
                .node(end("done", "${clean}"))
                .edge("a", "clean")
                .edge("clean", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().handler("clean", in -> NodeOutput.of("已清洗"));

        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("已清洗");
        // 只有 a 一次模型调用；Java 节点不额外产生模型往返
        assertThat(runtime.seen("clean").variables()).containsKey("a");
    }
}
