package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentAttributes;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-47：输出被 {@code max_tokens} 截断时必须留下声音。
 *
 * <p>上报的实测形态：{@code max-tokens: 64} + 会思考的本地模型 → 64 个 token 全花在 reasoning 上，
 * 于是 {@code finish_reason=length}、正文空串、最终答案为空，而<b>日志/事件/attributes 一个都没有</b>。
 * 这里把"修好之后应当看得见什么"钉住：</p>
 * <ul>
 *   <li>WARN 由 {@code DefaultLoopContext} 打（本测试不抓日志，抓的是等价的机器可读信号）；</li>
 *   <li>运行属性里出现 {@code benxin.truncated} / {@code benxin.truncatedSteps}；</li>
 *   <li>监听器收到 {@code model.truncated} 自定义事件；</li>
 *   <li>工作流场景：中间节点被截断不会因为最后一次响应正常而消失。</li>
 * </ul>
 */
class TruncationSignalTest {

    @Test
    @DisplayName("finishReason=LENGTH：写进运行属性 + 广播 model.truncated 事件")
    void lengthIsRecordedAndBroadcast() {
        List<String> eventTypes = new ArrayList<>();
        List<Object> eventPayloads = new ArrayList<>();
        AgentListener listener = new AgentListener() {
            @Override
            public void onCustomEvent(String type, Object payload) {
                eventTypes.add(type);
                eventPayloads.add(payload);
            }
        };
        ScriptedModel model = ScriptedModel.of(ScriptedModel.truncated(""));
        Agent agent = Agent.builder("truncation-probe")
                .model(model)
                .loopRegistry(BuiltinLoops.defaultRegistry())
                .loop("dsh-minimal")
                .listener(listener)
                .maxTokens(64)
                .maxSteps(3)
                .build();

        AgentResult result = agent.call("随便问点什么", "truncation-1");

        assertThat(result.finishReason())
                .as("插件一直都能正确解析 LENGTH，缺的是'解析出来之后有没有人管'")
                .isEqualTo(FinishReason.LENGTH);
        assertThat(result.text()).as("正文确实是空的 —— 正是最容易被误读成'模型不说话了'的形态").isEmpty();
        assertThat(result.attributes())
                .as("按 AgentAttributes 的键能查到截断这件事")
                .containsEntry(AgentAttributes.TRUNCATED, Boolean.TRUE)
                .containsEntry(AgentAttributes.TRUNCATED_STEPS, List.of(1));
        assertThat(eventTypes).contains("model.truncated");
        assertThat(eventPayloads).anySatisfy(payload -> {
            Map<?, ?> map = (Map<?, ?>) payload;
            assertThat(map.get("step")).isEqualTo(1);
            assertThat(map.get("textEmpty")).isEqualTo(true);
            assertThat(map.get("maxTokens")).isEqualTo(64);
        });
    }

    @Test
    @DisplayName("工作流：中间节点被截断也查得出来（最终 finishReason 已被后面的正常响应覆盖）")
    void workflowKeepsIntermediateTruncation() {
        WorkflowDefinition definition = WorkflowIo.read("""
                name: truncation-flow
                nodes:
                  - id: first
                    type: agent
                    prompt: "第一步：${input}"
                  - id: second
                    type: agent
                    prompt: "第二步：${first}"
                  - id: report
                    type: end
                    output: "结论=${second}"
                edges:
                  - from: first
                    to: second
                  - from: second
                    to: report
                """);
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.truncated(""),        // first 节点被截断，输出是空串
                ScriptedModel.text("完整答案"));     // second 节点正常说完，覆盖了 finishReason
        Agent agent = Agent.builder("wf-truncation")
                .model(model)
                .loop(new WorkflowLoop(definition))
                .maxSteps(10)
                .build();

        AgentResult result = agent.call("开始", "truncation-2");

        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run.truncatedNodes())
                .as("节点级的截断信号以前完全丢掉：WorkflowRun 只留最后一次响应的结束原因")
                .containsExactly("first");
        assertThat(run.hasTruncatedNodes()).isTrue();
        assertThat(result.finishReason())
                .as("最终 finishReason 是 STOP —— 只看 AgentResult 根本看不出中间被截断")
                .isEqualTo(FinishReason.STOP);
        assertThat(result.attributes().get(AgentAttributes.TRUNCATED))
                .as("因此还需要一个'整次运行出现过截断'的布尔信号")
                .isEqualTo(Boolean.TRUE);
    }
}
