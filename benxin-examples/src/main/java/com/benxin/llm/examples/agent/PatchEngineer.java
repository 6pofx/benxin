package com.benxin.llm.examples.agent;

import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

/**
 * 使用 {@code codex} 循环：先出计划、再做最小改动、最后必须用命令验证。
 *
 * <p>示例里没有开启任何文件/命令工具（{@code llm.tools.builtin-enabled=false}），
 * 这正是 Codex 循环的一个真实约束场景：模型应当意识到自己没有写权限，
 * 转而输出"将要执行的补丁"而不是硬改文件。想真跑起来，见 README 里的
 * {@code application-builtin-tools.yml} 说明。</p>
 */
@LlmAgent(
        model = "mock",
        loop = "codex",
        systemPrompt = "你是一名精确到行的 Java 重构工程师，改动越小越好。",
        maxSteps = 20)
public interface PatchEngineer {

    @SystemPrompt("""
            任务：{task}
            请先用 update_plan 明确步骤，再给出最小改动的补丁，最后说明如何验证。
            如果当前没有文件写入权限，就只输出补丁内容，不要声称已经改好了。
            """)
    String refactor(@User String code, @Ctx("task") String task);
}