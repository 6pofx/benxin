package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.support.SampleTools;
import com.benxin.llm.core.support.ScriptedModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化编排模式端到端：阶段流转、验收闸门与修复回路。
 *
 * <p>断言的重点是<b>调用次数</b>与<b>阶段序列</b> —— 一个"阶段机"是否真的按设计流转，
 * 只有在脚本化模型下才能被精确验证。</p>
 */
class StagedLoopTest {

    private static Agent agent(AgentLoop loop, LlmModel model, int maxSteps, Consumer<AgentBuilder> customizer) {
        AgentBuilder builder = Agent.builder("staged-agent")
                .model(model)
                .loop(loop)
                .maxSteps(maxSteps);
        if (customizer != null) {
            customizer.accept(builder);
        }
        return builder.build();
    }

    /** 收集 {@code staged.stage} 事件，得到真实的阶段序列。 */
    private static List<String> stageRecorder() {
        return new ArrayList<>();
    }

    private static AgentListener recordingInto(List<String> stages) {
        return new AgentListener() {
            @Override
            public void onCustomEvent(String type, Object payload) {
                if ((StagedLoop.NAME + ".stage").equals(type)) {
                    stages.add(String.valueOf(payload));
                }
            }
        };
    }

    @Test
    @DisplayName("校验不通过 → 修复 → 复验通过 → 汇总")
    void repairsAfterFailedVerification() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 读取配置\n2. 输出报告"),
                ScriptedModel.text("已读取配置"),
                ScriptedModel.text("已输出报告"),
                ScriptedModel.text("结论：不通过\n1. 缺少结论段落"),
                ScriptedModel.text("已补充结论段落"),
                ScriptedModel.text("结论：通过"),
                ScriptedModel.text("最终报告：全部完成"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(), model, 16,
                b -> b.listener(recordingInto(stages))).call("写一份报告");

        assertThat(result.text()).isEqualTo("最终报告：全部完成");
        assertThat(model.callCount()).isEqualTo(7);
        assertThat(stages).containsExactly(
                "plan", "execute", "verify", "repair", "verify", "synthesize");

        assertThat(result.attributes())
                .containsEntry(StagedLoop.VERDICT_ATTRIBUTE, "pass")
                .containsEntry(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE, 1)
                .containsEntry(StagedLoop.STAGE_ATTRIBUTE, "synthesize");
        assertThat((List<?>) result.attributes().get(StagedLoop.PLAN_ATTRIBUTE)).hasSize(2);
    }

    @Test
    @DisplayName("一次验收通过时不做多余修复")
    void skipsRepairWhenVerificationPasses() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 直接回答"),
                ScriptedModel.text("回答完毕"),
                ScriptedModel.text("结论：通过"),
                ScriptedModel.text("这是最终答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(), model, 12,
                b -> b.listener(recordingInto(stages))).call("回答问题");

        assertThat(result.text()).isEqualTo("这是最终答案");
        assertThat(model.callCount()).isEqualTo(4);
        assertThat(stages).containsExactly("plan", "execute", "verify", "synthesize");
        assertThat(result.attributes())
                .containsEntry(StagedLoop.VERDICT_ATTRIBUTE, "pass")
                .doesNotContainKey(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE);
    }

    @Test
    @DisplayName("修复轮次用尽后带着未解决的问题收尾，而不是无限修下去")
    void stopsAfterRepairBudgetExhausted() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 做事"),
                ScriptedModel.text("做完了"),
                ScriptedModel.text("结论：不通过\n1. 问题A"),
                ScriptedModel.text("尝试修了一次"),
                ScriptedModel.text("结论：不通过\n1. 问题A依然存在"),
                ScriptedModel.text("尽力给出的答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(), model, 12,
                b -> b.listener(recordingInto(stages))).call("做事");

        assertThat(result.text()).isEqualTo("尽力给出的答案");
        assertThat(model.callCount()).isEqualTo(6);
        // 只修一轮：默认 maxRepairRounds = 1
        assertThat(stages).containsExactly(
                "plan", "execute", "verify", "repair", "verify", "synthesize");
        assertThat(result.attributes()).containsEntry(StagedLoop.VERDICT_ATTRIBUTE, "fail");
    }

    @Test
    @DisplayName("maxRepairRounds 可配置：0 表示只验不修")
    void zeroRepairRoundsVerifiesOnly() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 做事"),
                ScriptedModel.text("做完了"),
                ScriptedModel.text("结论：不通过\n1. 问题A"),
                ScriptedModel.text("最终答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(true, 0), model, 12,
                b -> b.listener(recordingInto(stages))).call("做事");

        assertThat(result.text()).isEqualTo("最终答案");
        assertThat(stages).containsExactly("plan", "execute", "verify", "synthesize");
        assertThat(model.callCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("关闭验收闸门后不再有 verify/repair 阶段")
    void verificationCanBeDisabled() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 做事"),
                ScriptedModel.text("做完了"),
                ScriptedModel.text("最终答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(false, 0), model, 12,
                b -> b.listener(recordingInto(stages))).call("做事");

        assertThat(result.text()).isEqualTo("最终答案");
        assertThat(stages).containsExactly("plan", "execute", "synthesize");
        assertThat(result.attributes()).doesNotContainKey(StagedLoop.VERDICT_ATTRIBUTE);
    }

    @Test
    @DisplayName("计划解析不出来时降级为标准 tool-calling 循环，绝不空转")
    void degradesWhenPlanUnparsable() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("我觉得不用列计划，直接说吧"),
                ScriptedModel.text("直接答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(), model, 12,
                b -> b.listener(recordingInto(stages))).call("随便问点什么");

        assertThat(result.text()).isEqualTo("直接答案");
        assertThat(result.attributes()).doesNotContainKey(StagedLoop.PLAN_ATTRIBUTE);
        // 降级路径不会进入 execute/verify/synthesize
        assertThat(stages).containsExactly("plan");
    }

    @Test
    @DisplayName("执行阶段内部仍是一个完整的工具循环：工具调用会真实发生")
    void executesToolsInsideStage() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 查一下关键词"),
                ScriptedModel.toolCall("echo", "{\"text\":\"你好\"}"),
                ScriptedModel.text("查到了：echo:你好"),
                ScriptedModel.text("结论：通过"),
                ScriptedModel.text("完成"));

        AgentResult result = agent(new StagedLoop(), model, 16,
                b -> b.tool(SampleTools.echoTool("echo"))).call("查点东西");

        assertThat(result.text()).isEqualTo("完成");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("echo");
        assertThat(model.callCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("英文 PASS / FAIL 结论同样能识别")
    void recognizesEnglishVerdicts() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 做事"),
                ScriptedModel.text("做完了"),
                ScriptedModel.text("VERDICT: FAIL\n- missing summary"),
                ScriptedModel.text("修好了"),
                ScriptedModel.text("VERDICT: PASS"),
                ScriptedModel.text("最终答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(), model, 12,
                b -> b.listener(recordingInto(stages))).call("做事");

        assertThat(stages).containsExactly(
                "plan", "execute", "verify", "repair", "verify", "synthesize");
        assertThat(result.text()).isEqualTo("最终答案");
    }

    @Test
    @DisplayName("结论无法解析时判为通过，不把预算烧在反复验收上")
    void unparsableVerdictCountsAsPass() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("1. 做事"),
                ScriptedModel.text("做完了"),
                ScriptedModel.text("嗯……我看还行吧"),
                ScriptedModel.text("最终答案"));

        List<String> stages = stageRecorder();
        AgentResult result = agent(new StagedLoop(), model, 12,
                b -> b.listener(recordingInto(stages))).call("做事");

        assertThat(stages).containsExactly("plan", "execute", "verify", "synthesize");
        assertThat(result.text()).isEqualTo("最终答案");
    }

    @Test
    @DisplayName("loop 名与描述说明了阶段与修复轮次")
    void exposesNameAndDescription() {
        assertThat(new StagedLoop().name()).isEqualTo("staged");
        assertThat(new StagedLoop().description()).contains("校验").contains("修复");
        assertThat(new StagedLoop(false, 2).description()).contains("已关闭验收闸门");
    }
}
