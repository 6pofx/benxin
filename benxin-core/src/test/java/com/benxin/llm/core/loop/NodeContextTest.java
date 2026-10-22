package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.workflow.JavaFlow;
import com.benxin.llm.core.workflow.NodeContext;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowEngine;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRuntime;
import com.benxin.llm.core.workflow.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 节点上下文：默认隔离、事实显式传。
 *
 * <p>这里验证的是一句话：<b>节点看到什么，由你写进提示词的变量决定，而不是由"之前跑过谁"决定。</b>
 * 前者可复现、成本恒定；后者会随图的执行路径漂移。</p>
 *
 * <p>判定方式不看内部状态，只看 {@link ScriptedModel} 记录下来的<b>真实请求</b>：
 * 这条提示词到底带没带上文，请求里一目了然。</p>
 */
class NodeContextTest {

    private static Agent agent(WorkflowDefinition flow, ScriptedModel model) {
        return Agent.builder("ctx-agent")
                .model(model)
                .loop(CodeFlowLoop.of(flow))
                .maxSteps(12)
                .build();
    }

    private static WorkflowDefinition twoAgentFlow(boolean shared) {
        JavaFlow flow = JavaFlow.builder("ctx").start("S", "${input}");
        flow = shared
                ? flow.agentShared("A", "系统A", "第一问").agentShared("B", "系统B", "第二问：${A}")
                : flow.agent("A", "系统A", "第一问").agent("B", "系统B", "第二问：${A}");
        return flow.end("E", "${B}").chain("S", "A", "B", "E").build();
    }

    private static List<ChatMessage> byRole(ChatRequest request, Role role) {
        return request.messages().stream().filter(m -> m.role() == role).toList();
    }

    @Test
    @DisplayName("默认隔离：模型只收到本节点那条提示词，前面聊过什么一概看不见")
    void isolatedNodeSeesOnlyItsOwnPrompt() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("A 的答案"),
                ScriptedModel.text("B 的答案"));

        AgentResult result = agent(twoAgentFlow(false), model).call("任务");

        assertThat(result.text()).isEqualTo("B 的答案");

        ChatRequest second = model.requests().get(1);
        assertThat(byRole(second, Role.USER)).hasSize(1);
        assertThat(byRole(second, Role.ASSISTANT)).isEmpty();
        // 但它依然拿得到"显式传进来的事实"：${A} 已经在提示词里渲染好了
        assertThat(byRole(second, Role.USER).get(0).text()).isEqualTo("第二问：A 的答案");
    }

    @Test
    @DisplayName("shared 才继承对话：两个节点都共享时，第二个能看到第一个的问答")
    void sharedNodeInheritsTheConversation() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("A 的答案"),
                ScriptedModel.text("B 的答案"));

        agent(twoAgentFlow(true), model).call("任务");

        ChatRequest second = model.requests().get(1);
        // 任务原文 + 第一问 + 第二问
        assertThat(byRole(second, Role.USER)).hasSize(3);
        assertThat(byRole(second, Role.ASSISTANT)).hasSize(1);
        assertThat(byRole(second, Role.ASSISTANT).get(0).text()).isEqualTo("A 的答案");
    }

    @Test
    @DisplayName("隔离节点不往共享历史里写：它跑过什么，后面的节点一无所知")
    void isolatedNodesLeaveNothingInTheSharedHistory() {
        // A 隔离、B 共享：B 只看得到任务原文与自己这句，A 的问答不在历史里
        WorkflowDefinition flow = JavaFlow.builder("mixed")
                .start("S", "${input}")
                .agent("A", "系统A", "第一问")
                .agentShared("B", "系统B", "第二问：${A}")
                .end("E", "${B}")
                .chain("S", "A", "B", "E")
                .build();
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("A 的答案"),
                ScriptedModel.text("B 的答案"));

        agent(flow, model).call("任务");

        ChatRequest second = model.requests().get(1);
        assertThat(byRole(second, Role.USER)).hasSize(2);   // 任务 + 第二问
        assertThat(byRole(second, Role.ASSISTANT)).isEmpty();
        // A 的结论只能通过 ${A} 显式传进来 —— 这正是我们想要的
        assertThat(byRole(second, Role.USER).get(1).text()).isEqualTo("第二问：A 的答案");
    }

    @Test
    @DisplayName("隔离节点的成本恒定：跑多少个节点，每次请求都只有一条用户消息")
    void isolatedHistoryDoesNotGrow() {
        WorkflowDefinition flow = JavaFlow.builder("long")
                .start("S", "${input}")
                .agent("A", "系统A", "第一步")
                .agent("B", "系统B", "第二步：${A}")
                .agent("C", "系统C", "第三步：${B}")
                .end("E", "${C}")
                .chain("S", "A", "B", "C", "E")
                .build();

        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("一"),
                ScriptedModel.text("二"),
                ScriptedModel.text("三"));

        agent(flow, model).call("任务");

        assertThat(model.requests()).hasSize(3);
        for (ChatRequest request : model.requests()) {
            assertThat(byRole(request, Role.USER)).hasSize(1);
            assertThat(byRole(request, Role.ASSISTANT)).isEmpty();
        }
        assertThat(byRole(model.requests().get(2), Role.USER).get(0).text())
                .isEqualTo("第三步：二");
    }

    @Test
    @DisplayName("YAML 也能声明：context: shared 能反序列化，不写就是隔离")
    void yamlCarriesTheSwitch() {
        WorkflowDefinition shared = WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: agent
                    prompt: hi
                    context: shared
                """);
        assertThat(firstNode(shared).context()).isEqualTo(NodeContext.SHARED);

        WorkflowDefinition isolated = WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: agent
                    prompt: hi
                """);
        assertThat(firstNode(isolated).context()).isEqualTo(NodeContext.ISOLATED);

        // 默认值不写进输出，保持文件干净
        assertThat(WorkflowIo.toMap(isolated).toString()).doesNotContain("context");
        assertThat(WorkflowIo.toMap(shared).toString()).contains("context=shared");
    }

    private static WorkflowNode firstNode(WorkflowDefinition definition) {
        return definition.nodes().get(0);
    }

    @Test
    @DisplayName("提示词里留了没人写过的变量：报一条事件，而不是让模型凭空编")
    void reportsUnresolvedPlaceholders() {
        List<String> events = new ArrayList<>();
        WorkflowDefinition flow = JavaFlow.builder("unresolved")
                .start("S", "${input}")
                .agent("A", "系统", "金额：${missing}")
                .end("E", "${A}")
                .chain("S", "A", "E")
                .build();

        WorkflowRuntime runtime = new WorkflowRuntime() {
            @Override
            public String callAgent(String prompt, WorkflowNode node) {
                return "ok";
            }

            @Override
            public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
                return "";
            }

            @Override
            public void emit(String type, Object payload) {
                events.add(type);
            }

            @Override
            public boolean outOfBudget() {
                return false;
            }
        };

        new WorkflowEngine().run(flow, new WorkflowState("任务"), runtime);

        assertThat(events).contains("workflow.unresolved-variable");
    }
}
