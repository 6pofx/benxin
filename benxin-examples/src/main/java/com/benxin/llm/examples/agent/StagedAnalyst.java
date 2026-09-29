package com.benxin.llm.examples.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * <b>结构化编排模式</b>示例：{@code loop = "staged"} 是一条写死在代码里的阶段流水线。
 *
 * <p>与 {@link ReleaseNoteWriter} 的对照，正是工作流模式两条路线的分界：</p>
 * <ul>
 *   <li>那个是"<b>图由外部定义</b>"：流程改了改 YAML，适合固定、需要可审计的流程；</li>
 *   <li>这个是"<b>流程由代码固化</b>"：规划 → 执行 → 校验 → 修复 → 汇总，
 *       适合任务形态多变、但每次都想拿到"先做后验"收益的场景。</li>
 * </ul>
 *
 * <p>它比 {@code plan-execute} 多一道验收闸门：汇总之前会以"验收者"身份独立复查一次，
 * 不通过就带着具体问题回到修复阶段。离线 Mock 模型对验收请求固定回答"结论：通过"，
 * 因此这个示例演示的是完整走通的一遍（含校验阶段）。</p>
 */
@LlmAgent(
        model = "mock",
        loop = "staged",
        systemPrompt = "你是一名严谨的技术分析师，先规划再动手，结论要有依据。",
        maxSteps = 16)
public interface StagedAnalyst {

    /** 给出一个分析主题，返回经过"规划—执行—验收—汇总"之后的结论。 */
    String analyze(String topic);
}
