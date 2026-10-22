package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.workflow.JavaFlow;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 代码编排的 Loop 入口：{@code loop = "flow"}。
 *
 * <p>重点在两件事：<b>它真的能驱动一个真 Agent</b>，以及<b>它不肯吃 YAML</b> ——
 * 后者是"两种模式单向不混用"的具体落点。</p>
 */
class CodeFlowLoopTest {

    private static WorkflowDefinition reviewFlow() {
        return JavaFlow.builder("review")
                .start("begin", "评审：${input}")
                .agent("analyze", "分析：${begin}")
                .code("lint", in -> NodeOutput.of("无阻断问题"))
                .end("done", "分析=${analyze}｜检查=${lint}")
                .chain("begin", "analyze", "lint", "done")
                .build();
    }

    private static Agent agent(AgentLoop loop, LlmModel model, int maxSteps) {
        return Agent.builder("flow-agent")
                .model(model)
                .loop(loop)
                .maxSteps(maxSteps)
                .build();
    }

    @Test
    @DisplayName("绑定好的代码编排图能跑完一个真 Agent 调用")
    void runsThroughAgent() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("两处命名问题"));

        AgentResult result = agent(CodeFlowLoop.of(reviewFlow()), model, 8).call("这段代码");

        assertThat(result.text()).isEqualTo("分析=两处命名问题｜检查=无阻断问题");
        assertThat(model.callCount()).isEqualTo(1);
        // 运行信息与 YAML 模式同口径写回 attributes
        assertThat(result.attributes()).containsKey(WorkflowLoop.RUN_ATTRIBUTE);
    }

    @Test
    @DisplayName("注册名是 flow，描述能看出是代码编排")
    void exposesNameAndDescription() {
        CodeFlowLoop bound = CodeFlowLoop.of(reviewFlow());

        assertThat(bound.name()).isEqualTo("flow");
        assertThat(bound.description()).contains("代码编排").contains("review");

        CodeFlowLoop unbound = new CodeFlowLoop();
        assertThat(unbound.name()).isEqualTo("flow");
        assertThat(unbound.description()).contains("JavaFlow");
    }

    @Test
    @DisplayName("没绑定图时给出一条能照做的错误")
    void failsClearlyWhenUnbound() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("不该走到这里"));

        assertThatThrownBy(() -> agent(new CodeFlowLoop(), model, 4).call("x"))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("代码编排模式")
                .hasMessageContaining("CodeFlowLoop.of")
                .hasMessageContaining("loop=workflow");
    }

    @Test
    @DisplayName("单向不混用：flow 不读 benxin.workflow 属性里的 YAML")
    void ignoresYamlFromAttributes() {
        String yaml = """
                name: from-yaml
                nodes:
                  - id: a
                    prompt: x
                """;
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("不该走到这里"));

        // 即便把定义塞进属性，flow 也只认构造时绑定的图 → 没绑定就报错
        assertThatThrownBy(() -> agent(new CodeFlowLoop(), model, 4)
                .call("s1", List.of(ChatMessage.user("x")),
                        Map.of(WorkflowLoop.ATTRIBUTE, yaml), null))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("代码编排模式");
    }

    @Test
    @DisplayName("内置 Loop 注册表里包含 flow")
    void isRegisteredAsBuiltin() {
        LoopRegistry registry = BuiltinLoops.defaultRegistry();

        assertThat(registry.names()).contains("flow", "workflow", "staged");
        assertThat(registry.get("flow")).isInstanceOf(CodeFlowLoop.class);
    }

    // ------------------------------------------------------------------
    // 节点级系统提示词
    // ------------------------------------------------------------------

    @Test
    @DisplayName("节点级系统提示词真的走系统通道：追加在 Agent 级之后，不混进用户消息")
    void nodeSystemPromptGoesToSystemChannel() {
        WorkflowDefinition flow = JavaFlow.builder("sys")
                .start("begin", "${input}")
                .agent("judge", "只输出以下三个词之一：通过 / 驳回 / 需补充材料", "请判断这份材料：${begin}")
                .end("done", "${judge}")
                .chain("begin", "judge", "done")
                .build();

        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("通过"));

        AgentResult result = Agent.builder("sys-agent")
                .model(model)
                .loop(CodeFlowLoop.of(flow))
                .systemPrompt("你是审批助手。")
                .maxSteps(6)
                .build()
                .call("材料 A");

        assertThat(result.text()).isEqualTo("通过");

        ChatRequest request = model.lastRequest();
        // 系统通道里：Agent 级在前，节点级在后
        assertThat(request.systemText())
                .contains("你是审批助手")
                .contains("只输出以下三个词之一");
        // 用户消息里没有混入系统提示词
        assertThat(request.messages())
                .filteredOn(m -> m.role() == Role.USER)
                .noneMatch(m -> m.text().contains("只输出以下三个词之一"));
    }

    // ------------------------------------------------------------------
    // 多出口 + 回边
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 输出契约
    // ------------------------------------------------------------------

    private static WorkflowDefinition verdictFlow() {
        return JavaFlow.builder("verdict")
                .start("begin", "${input}")
                .agent("F", "你是审核员", "请给出结论", "通过", "驳回")
                .routeFrom("F", "F", c -> c
                        .caseOf("通过", "pass")
                        .caseOf("驳回", "reject"))
                .end("pass", "PASS[${F}]")
                .end("reject", "REJECT[${F}]")
                .chain("begin", "F")
                .build();
    }

    @Test
    @DisplayName("输出契约：模型套了 Markdown、加了客气话，仍然进对分支；黑板上留下的是干净枚举值")
    void contractNormalizesIntoEnumOnBlackboard() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("好的，**结论：通过**。"));

        AgentResult result = agent(CodeFlowLoop.of(verdictFlow()), model, 8).call("材料");

        // 注意 ${F} 取到的是"通过"两个干净的汉字，而不是模型原文
        assertThat(result.text()).isEqualTo("PASS[通过]");
    }

    @Test
    @DisplayName("输出契约：第一次含糊不清就纠正重问，第二次合规即放行")
    void contractRetriesWhenModelWanders() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("我看了一下，感觉还行"),
                ScriptedModel.text("通过"));

        AgentResult result = agent(CodeFlowLoop.of(verdictFlow()), model, 8).call("材料");

        assertThat(result.text()).isEqualTo("PASS[通过]");
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("输出契约：反复不合规就让节点失败，绝不把模糊答案当成枚举值塞进图里")
    void contractFailsWhenModelKeepsWandering() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("不确定"),
                ScriptedModel.text("还是不确定"),
                ScriptedModel.text("真的不确定"));

        assertThatThrownBy(() -> agent(CodeFlowLoop.of(verdictFlow()), model, 16).call("材料"))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("不在契约")
                .hasMessageContaining("不确定");
        // 首次 + 两次纠正重试，之后不再无限重问
        assertThat(model.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("输出契约只写在 agent 节点上，别的节点带 choice 会在构建期报错")
    void choiceOnlyBelongsToAgentNodes() {
        assertThatThrownBy(() -> WorkflowDefinition.builder("bad")
                .node(com.benxin.llm.core.workflow.WorkflowNode.builder("a")
                        .type(com.benxin.llm.core.workflow.NodeType.CODE)
                        .behavior(in -> NodeOutput.of("x"))
                        .choice(java.util.List.of("通过"))
                        .build())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有 agent 节点支持 choice");
    }

    @Test
    @DisplayName("三出口分支 + 回到入口节点重跑 + 多个终点：图里最复杂的那类结构确实可表达")
    void reworkLoopsBackToEntryAndCanEndInSeveralPlaces() {
        WorkflowDefinition flow = JavaFlow.builder("rework")
                .start("begin", "材料：${input}")
                .agent("judge", "只输出：通过 / 驳回 / 需补充材料", "请判断：${begin}")
                .branchOn("route", "judge", c -> c
                        .caseOf("通过", "approve")
                        .caseOf("驳回", "reject")
                        .caseOf("需补充材料", "supplement"))
                .code("supplement", in -> NodeOutput.of("已补材料", Map.of("supplemented", true)))
                .branchOn("recheck", "supplemented", c -> c
                        .caseOf("true", "begin")        // 回边：重新走入口节点
                        .otherwise("giveup"))
                .end("approve", "审批通过：${judge}")
                .end("reject", "已驳回：${judge}")
                .end("giveup", "放弃：${judge}")
                .chain("begin", "judge", "route")
                .chain("supplement", "recheck")
                .build();

        // 第一次判"需补充材料"→ 补材料 → recheck 为 true → 回到 begin；第二次判"通过"
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("需补充材料"),
                ScriptedModel.text("通过"));

        AgentResult result = agent(CodeFlowLoop.of(flow), model, 12).call("材料 A");

        assertThat(result.text()).isEqualTo("审批通过：通过");
        assertThat(model.callCount()).isEqualTo(2);

        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run.visited()).containsExactly(
                "begin", "judge", "route", "supplement", "recheck",
                "begin", "judge", "route", "approve");
        assertThat(run.completed()).isTrue();
    }
}
