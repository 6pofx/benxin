package com.benxin.llm.examples.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.examples.tool.WeatherTools;

/**
 * <b>声明式工作流模式</b>示例：接口看不出任何差别，只是 loop 名指向了一张 YAML 图。
 *
 * <p>{@code workflow:release-notes} 里的 <b>release-notes</b> 就是
 * {@code llm.workflows.<key>} 的 key。也就是说：<b>换一种工作方式不需要改接口，
 * 甚至不需要写 Java 代码</b> —— 改那张图就够了。</p>
 *
 * <p>工具按需装配：图里的 tool 节点用到了 {@code get_current_time}，
 * 它来自 {@link WeatherTools}，所以这里必须把该类带上；否则那个节点会因为
 * "未知工具"拿到一个错误结果（而不是让整个工作流崩掉）。</p>
 */
@LlmAgent(
        model = "mock",
        loop = "workflow:release-notes",
        tools = {WeatherTools.class},
        maxSteps = 12)
public interface ReleaseNoteWriter {

    /** 传入一次变更的描述，返回按图生成的完整发布说明。 */
    String draft(String changeDescription);
}
