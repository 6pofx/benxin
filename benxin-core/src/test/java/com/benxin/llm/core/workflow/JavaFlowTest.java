package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 代码编排：{@link JavaFlow} 写出来的图与 YAML 写出来的图跑的是同一个引擎。
 *
 * <p>这里验证两件事：<b>拓扑真的按代码里接的线走</b>，以及<b>两种写法在语义上完全一致</b>
 * （边序优先级、护栏、重试、补偿、变量回写）。</p>
 */
class JavaFlowTest {

    /** 按节点 id 编排返回值的假运行时；CODE 节点直接跑绑定的对象，不经过它。 */
    private static final class FakeRuntime implements WorkflowRuntime {

        private final Map<String, String> agentOutputs = new LinkedHashMap<>();
        private final Map<String, String> toolOutputs = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> toolArgs = new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();

        private int budget = Integer.MAX_VALUE;
        private int calls;

        FakeRuntime agentOutput(String nodeId, String text) {
            agentOutputs.put(nodeId, text);
            return this;
        }

        FakeRuntime toolOutput(String nodeId, String text) {
            toolOutputs.put(nodeId, text);
            return this;
        }

        @Override
        public String callAgent(String prompt, WorkflowNode node) {
            calls++;
            return agentOutputs.getOrDefault(node.id(), "out-" + node.id());
        }

        @Override
        public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
            calls++;
            toolArgs.put(node.id(), args);
            return toolOutputs.getOrDefault(node.id(), "tool-" + tool);
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

    private static WorkflowRun run(WorkflowDefinition definition, FakeRuntime runtime, String input) {
        return new WorkflowEngine().run(definition, new WorkflowState(input), runtime);
    }

    // ------------------------------------------------------------------
    // 拓扑与执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按代码里接的线走完一张图，END 的渲染结果就是最终答案")
    void runsTheWiredTopology() {
        WorkflowDefinition flow = JavaFlow.builder("t")
                .start("begin", "任务：${input}")
                .agent("analyze", "分析：${begin}")
                .code("lint", in -> NodeOutput.of("检查通过"))
                .end("done", "分析=${analyze}｜检查=${lint}")
                .chain("begin", "analyze", "lint", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime().agentOutput("analyze", "发现 2 处问题");
        WorkflowRun result = run(flow, runtime, "评审这段代码");

        assertThat(result.output()).isEqualTo("分析=发现 2 处问题｜检查=检查通过");
        assertThat(result.visited()).containsExactly("begin", "analyze", "lint", "done");
        assertThat(result.completed()).isTrue();
    }

    @Test
    @DisplayName("谓词边：条件成立走条件边，不成立落到默认边")
    void predicateRoutesFlow() {
        // 注意节点 id 不能与被读的变量同名：同名的键永远代表该节点的输出
        WorkflowDefinition flow = JavaFlow.builder("route")
                .code("assess", in -> NodeOutput.of("评分完成", Map.of("score", 85)))
                .branch("decide")
                .end("pass", "通过")
                .end("fail", "不通过")
                .chain("assess", "decide")
                .edge("decide", "pass", v -> Integer.parseInt(v.text("score")) >= 60)
                .edge("decide", "fail")
                .build();

        assertThat(run(flow, new FakeRuntime(), "x").output()).isEqualTo("通过");

        WorkflowDefinition low = JavaFlow.builder("route-low")
                .code("assess", in -> NodeOutput.of("评分完成", Map.of("score", 40)))
                .branch("decide")
                .end("pass", "通过")
                .end("fail", "不通过")
                .chain("assess", "decide")
                .edge("decide", "pass", v -> Integer.parseInt(v.text("score")) >= 60)
                .edge("decide", "fail")
                .build();

        assertThat(run(low, new FakeRuntime(), "x").output()).isEqualTo("不通过");
    }

    @Test
    @DisplayName("谓词看到的是活黑板：能读上一步刚写进去的变量、内置量与原始类型")
    void predicateSeesLiveState() {
        List<String> seen = new ArrayList<>();
        WorkflowDefinition flow = JavaFlow.builder("live")
                .code("compute", in -> NodeOutput.of("算完了",
                        Map.of("count", 3, "tags", List.of("a", "b"))))
                .branch("decide")
                .end("yes", "是")
                .end("no", "否")
                .chain("compute", "decide")
                .edge("decide", "yes", v -> {
                    seen.add("input=" + v.input());
                    seen.add("last=" + v.last());
                    seen.add("count=" + v.raw("count"));
                    seen.add("tags=" + v.raw("tags"));
                    seen.add("has(count)=" + v.has("count"));
                    seen.add("has(nope)=" + v.has("nope"));
                    return v.raw("count") instanceof Integer n && n > 1
                            && v.raw("tags") instanceof List<?> tags && tags.size() == 2;
                })
                .edge("decide", "no")
                .build();

        assertThat(run(flow, new FakeRuntime(), "原始任务").output()).isEqualTo("是");
        assertThat(seen).contains(
                "input=原始任务",
                "last=算完了",
                "count=3",
                "tags=[a, b]",
                "has(count)=true",
                "has(nope)=false");
    }

    @Test
    @DisplayName("边序即优先级：两条谓词都成立时走先声明的那条")
    void edgeOrderIsPriority() {
        WorkflowDefinition flow = JavaFlow.builder("order")
                .start("begin")
                .branch("decide")
                .end("first", "第一")
                .end("second", "第二")
                .chain("begin", "decide")
                .edge("decide", "first", v -> true)
                .edge("decide", "second", v -> true)
                .build();

        assertThat(run(flow, new FakeRuntime(), "x").output()).isEqualTo("第一");
    }

    @Test
    @DisplayName("谓词抛异常时定位到具体节点与边，而不是一句裸 NPE")
    void predicateFailureIsLocated() {
        WorkflowDefinition flow = JavaFlow.builder("boom")
                .start("begin")
                .branch("decide")
                .end("done", "不该到这里")
                .chain("begin", "decide")
                .edge("decide", "done", v -> {
                    throw new IllegalStateException("谓词里写错了");
                })
                .build();

        FakeRuntime runtime = new FakeRuntime();
        WorkflowException error = catchThrowableOfType(
                () -> run(flow, runtime, "x"), WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.nodeId()).isEqualTo("decide");
        assertThat(error.getMessage())
                .contains("[decide]")
                .contains("decide -> done")
                .contains("谓词里写错了");
        assertThat(runtime.events).contains("workflow.edge-error");
    }

    // ------------------------------------------------------------------
    // 节点行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("代码节点带回的变量供后续节点与模板使用；none() 不覆盖 last")
    void codeNodePublishesVariables() {
        WorkflowDefinition flow = JavaFlow.builder("vars")
                .start("begin")
                .code("stat", in -> NodeOutput.of("统计完成", Map.of("words", 12)))
                .code("silent", in -> NodeOutput.none())
                .end("done", "字数=${words}｜last=${last}")
                .chain("begin", "stat", "silent", "done")
                .build();

        // begin 先把 last 设成任务原文；stat 覆盖；silent 不应覆盖
        assertThat(run(flow, new FakeRuntime(), "任务").output())
                .isEqualTo("字数=12｜last=统计完成");
    }

    @Test
    @DisplayName("代码节点能拿到渲染后的参数与只读黑板快照")
    void codeNodeReceivesRenderedArgs() {
        List<String> captured = new ArrayList<>();
        WorkflowDefinition flow = JavaFlow.builder("args")
                .start("begin", "前缀-${input}")
                .code("peek", in -> {
                    captured.add("arg=" + in.arg("text"));
                    captured.add("input=" + in.input());
                    captured.add("var(begin)=" + in.variable("begin"));
                    captured.add("varsHasInput=" + in.variables().containsKey("input"));
                    return NodeOutput.of("ok");
                }, JavaFlow.args("text", "${input}"))
                .end("done", "${peek}")
                .chain("begin", "peek", "done")
                .build();

        run(flow, new FakeRuntime(), "正文");
        assertThat(captured).containsExactly(
                "arg=正文",              // args 已渲染
                "input=正文",            // 内置量走专门访问器
                "var(begin)=前缀-正文",  // 上一个节点的输出
                "varsHasInput=false");   // 内置量不在 variables 里
    }

    @Test
    @DisplayName("同一种写法可以混用内置节点与代码节点")
    void mixesBuiltinAndCodeNodes() {
        WorkflowDefinition flow = JavaFlow.builder("mix")
                .start("begin")
                .agent("think", "想一想：${begin}")
                .tool("lookup", "search", JavaFlow.args("q", "${think}"))
                .set("mark", JavaFlow.vars("phase", "已检索"))
                .code("wrap", in -> NodeOutput.of("[" + in.variable("lookup") + "]"))
                .end("done", "${phase}｜${wrap}")
                .chain("begin", "think", "lookup", "mark", "wrap", "done")
                .build();

        FakeRuntime runtime = new FakeRuntime()
                .agentOutput("think", "关键词")
                .toolOutput("lookup", "命中 3 条");
        WorkflowRun result = run(flow, runtime, "x");

        assertThat(runtime.toolArgs.get("lookup")).containsEntry("q", "关键词");
        assertThat(result.output()).isEqualTo("已检索｜[命中 3 条]");
    }

    // ------------------------------------------------------------------
    // 容错与护栏（语义与 YAML 模式必须一致）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("代码节点的 retry 生效")
    void codeNodeRetryWorks() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition flow = JavaFlow.builder("retry")
                .start("begin")
                .node("flaky", NodeType.CODE, b -> b
                        .behavior(in -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new IllegalStateException("再试一次");
                            }
                            return NodeOutput.of("第三次成功");
                        })
                        .retry(2))
                .end("done", "${flaky}")
                .chain("begin", "flaky", "done")
                .build();

        WorkflowRun result = run(flow, new FakeRuntime(), "x");

        assertThat(attempts).hasValue(3);
        assertThat(result.output()).isEqualTo("第三次成功");
    }

    @Test
    @DisplayName("continueOnError + 补偿分支：出边能读 ${error}")
    void codeNodeCompensation() {
        WorkflowDefinition flow = JavaFlow.builder("compensate")
                .start("begin")
                .node("boom", NodeType.CODE, b -> b
                        .behavior(in -> {
                            throw new IllegalStateException("校验没通过");
                        })
                        .continueOnError(true))
                .end("recover", "补偿：${error}")
                .end("normal", "正常")
                .chain("begin", "boom")
                .edge("boom", "recover", v -> !v.error().isBlank())
                .edge("boom", "normal")
                .build();

        WorkflowRun result = run(flow, new FakeRuntime(), "x");

        assertThat(result.output()).startsWith("补偿：").contains("[boom]").contains("校验没通过");
    }

    @Test
    @DisplayName("不调模型的死循环仍由单节点访问上限拦住")
    void visitGuardStillWorks() {
        WorkflowDefinition flow = JavaFlow.builder("loop")
                .code("a", in -> NodeOutput.none())
                .branch("b")
                .edge("a", "b")
                .edge("b", "a")
                .maxVisitsPerNode(3)
                .build();

        WorkflowException error = catchThrowableOfType(
                () -> run(flow, new FakeRuntime(), "x"), WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("死循环").contains("maxVisitsPerNode");
    }

    // ------------------------------------------------------------------
    // 校验与序列化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("复用同一套加载期校验：重复 id、边指向不存在的节点都会在 build 时报错")
    void buildValidatesLikeYaml() {
        assertThatThrownBy(() -> JavaFlow.builder("dup")
                .start("a")
                .start("a")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点 id 重复");

        assertThatThrownBy(() -> JavaFlow.builder("dangling")
                .start("a")
                .end("b")
                .edge("a", "nope")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在节点");
    }

    @Test
    @DisplayName("代码节点与谓词边都明确拒绝序列化，绝不写出一份读回来会变形的文件")
    void refusesToSerializeJavaObjects() {
        WorkflowDefinition withCode = JavaFlow.builder("code-flow")
                .code("a", in -> NodeOutput.of("x"))
                .build();

        assertThatThrownBy(() -> WorkflowIo.writeJson(withCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法序列化")
                .hasMessageContaining("[a]");

        WorkflowDefinition withPredicate = JavaFlow.builder("predicate-flow")
                .start("a")
                .end("b")
                .edge("a", "b", v -> true)
                .build();

        assertThatThrownBy(() -> WorkflowIo.writeJson(withPredicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("谓词")
                .hasMessageContaining("a -> b");
    }

    @Test
    @DisplayName("YAML 里写 type: code 会被拒，并说明代码节点只能在 Java 里构造")
    void yamlCannotAuthorCodeNode() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: code
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须绑定一个 WorkflowHandler")
                .hasMessageContaining("JavaFlow");
    }

    @Test
    @DisplayName("同一条边不能同时给表达式与谓词")
    void rejectsBothConditionKinds() {
        // 边的校验随整张图一起做（与 YAML 路径同一个入口）
        WorkflowEdge both = WorkflowEdge.builder("a", "b")
                .when("${x} == 1")
                .condition(v -> true)
                .build();

        assertThatThrownBy(() -> WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.START).build())
                .node(WorkflowNode.builder("b").type(NodeType.END).build())
                .edge(both)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能二选一");
    }

    @Test
    @DisplayName("DSL 的计数与 toString 便于排错")
    void exposesShape() {
        JavaFlow flow = JavaFlow.builder("shape")
                .start("a")
                .end("b")
                .edge("a", "b");

        assertThat(flow.nodeCount()).isEqualTo(2);
        assertThat(flow.edgeCount()).isEqualTo(1);
        assertThat(flow.toString()).contains("shape").contains("2 节点").contains("1 边");
    }
}
