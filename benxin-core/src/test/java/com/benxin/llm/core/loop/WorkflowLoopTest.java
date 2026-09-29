package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.support.SampleTools;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式工作流模式端到端：定义 → 引擎 → LoopContext → 真实 Agent。
 *
 * <p>这里用的是真 Agent 加假模型，因此"分支真的改变了调用次数""工具节点真的执行了工具"
 * 这类断言才有意义 —— 它们正是工作流模式存在的理由。</p>
 */
class WorkflowLoopTest {

    private static final String REVIEW = """
            name: review
            nodes:
              - id: begin
                type: start
                prompt: "任务：${input}"
              - id: analyze
                type: agent
                prompt: "请分析：${begin}"
              - id: route
                type: branch
              - id: fix
                type: agent
                prompt: "请修复：${analyze}"
              - id: report
                type: end
                output: "结论=${analyze}｜修复=${fix:-无}"
            edges:
              - from: begin
                to: analyze
              - from: analyze
                to: route
              - from: route
                to: fix
                when: "${analyze} contains 严重"
              - from: route
                to: report
              - from: fix
                to: report
            """;

    private static Agent agent(AgentLoop loop, LlmModel model, Consumer<AgentBuilder> customizer) {
        AgentBuilder builder = Agent.builder("wf-agent")
                .model(model)
                .loop(loop)
                .maxSteps(10);
        if (customizer != null) {
            customizer.accept(builder);
        }
        return builder.build();
    }

    @Test
    @DisplayName("条件成立时走修复分支：多消耗一次模型调用")
    void takesConditionalBranch() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("发现严重的内存泄漏"),
                ScriptedModel.text("已修复"));

        AgentResult result = agent(new WorkflowLoop(WorkflowIo.read(REVIEW)), model, null)
                .call("评审这段代码");

        assertThat(result.text()).isEqualTo("结论=发现严重的内存泄漏｜修复=已修复");
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(result.steps()).isEqualTo(2);
    }

    @Test
    @DisplayName("条件不成立时走默认边：模型只被调用一次")
    void takesDefaultBranch() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("没什么问题"));

        AgentResult result = agent(new WorkflowLoop(WorkflowIo.read(REVIEW)), model, null)
                .call("评审这段代码");

        assertThat(result.text()).isEqualTo("结论=没什么问题｜修复=无");
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("运行信息与黑板变量会写回 attributes，供调用方复盘")
    void exposesRunAndVariables() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("发现严重问题"));

        AgentResult result = agent(new WorkflowLoop(WorkflowIo.read(REVIEW)), model, null)
                .call("评审");

        assertThat(result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE)).isInstanceOf(WorkflowRun.class);
        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run.workflow()).isEqualTo("review");
        assertThat(run.visited()).containsExactly("begin", "analyze", "route", "fix", "report");

        @SuppressWarnings("unchecked")
        Map<String, Object> variables =
                (Map<String, Object>) result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);
        assertThat(variables).containsEntry("analyze", "发现严重问题");
    }

    @Test
    @DisplayName("定义可以逐次调用传入，同一个 Loop 能跑不同的图")
    void acceptsDefinitionFromAttributes() {
        String inline = """
                name: inline-flow
                nodes:
                  - id: a
                    type: agent
                    prompt: "回答：${input}"
                  - id: done
                    type: end
                    output: "得到 ${a}"
                edges:
                  - from: a
                    to: done
                """;
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("四十二"));
        Agent agent = agent(new WorkflowLoop(), model, null);

        AgentResult result = agent.call("s1", List.of(ChatMessage.user("问题")),
                Map.of(WorkflowLoop.ATTRIBUTE, inline), null);

        assertThat(result.text()).isEqualTo("得到 四十二");
    }

    @Test
    @DisplayName("定义可以传 YAML 文本，也可以传已解析好的对象")
    void acceptsDefinitionObject() {
        WorkflowDefinition definition = WorkflowIo.read(REVIEW);
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("没问题"));

        AgentResult result = agent(new WorkflowLoop(), model, null).call("s1",
                List.of(ChatMessage.user("评审")),
                Map.of(WorkflowLoop.ATTRIBUTE, definition), null);

        assertThat(result.text()).isEqualTo("结论=没问题｜修复=无");
    }

    @Test
    @DisplayName("工具节点真的执行了工具，结果进入后续模板")
    void toolNodeExecutesRealTool() {
        String yaml = """
                name: tooling
                nodes:
                  - id: a
                    type: agent
                    prompt: "产出关键词"
                  - id: echo
                    type: tool
                    tool: echo
                    args:
                      text: "${a}"
                  - id: done
                    type: end
                    output: "回显=${echo}"
                edges:
                  - from: a
                    to: echo
                  - from: echo
                    to: done
                """;
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("你好世界"));

        AgentResult result = agent(new WorkflowLoop(WorkflowIo.read(yaml)), model,
                b -> b.tool(SampleTools.echoTool("echo"))).call("开始");

        assertThat(result.text()).isEqualTo("回显=echo:你好世界");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("echo");
    }

    @Test
    @DisplayName("工作流事件透传到监听器，便于 UI 展示进度")
    void emitsEventsToListener() {
        List<String> custom = new ArrayList<>();
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("没有问题"));

        agent(new WorkflowLoop(WorkflowIo.read(REVIEW)), model, b -> b.listener(new AgentListener() {
            @Override
            public void onCustomEvent(String type, Object payload) {
                custom.add(type);
            }
        })).call("评审");

        assertThat(custom).contains("workflow.start", "workflow.node", "workflow.end");
        assertThat(custom.indexOf("workflow.start")).isZero();
        assertThat(custom.get(custom.size() - 1)).isEqualTo("workflow.end");
    }

    @Test
    @DisplayName("既没绑定定义、属性里也没有时，报错要直接给出三种补法")
    void failsWithActionableMessageWhenNoDefinition() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("不该走到这里"));

        assertThatThrownBy(() -> agent(new WorkflowLoop(), model, null).call("评审"))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("benxin.workflow")
                .hasMessageContaining("llm.workflows");
    }

    @Test
    @DisplayName("loop 名与描述会带上工作流名，/llm/loops 端点一眼能分辨")
    void exposesNameAndDescription() {
        WorkflowLoop bound = new WorkflowLoop(WorkflowIo.read(REVIEW));

        assertThat(bound.name()).isEqualTo("workflow:review");
        // REVIEW 没有写 description，于是回退成"几个节点几条边"
        assertThat(bound.description()).isEqualTo("声明式工作流 [review]：5 个节点 / 5 条边");

        assertThat(new WorkflowLoop().name()).isEqualTo("workflow");
        assertThat(new WorkflowLoop().description()).contains("benxin.workflow");
    }
}
