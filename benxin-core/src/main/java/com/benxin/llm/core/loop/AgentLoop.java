package com.benxin.llm.core.loop;

/**
 * Agent 循环 —— 本心最核心的可替换点。
 *
 * <p>"Loop"决定 Agent 如何思考：ReAct 的一步一观察、Claude Code 的主循环加子代理、
 * Codex 的 turn 制计划与补丁、DSH 极简的一问一答。想自定义一种工作方式，
 * 实现本接口并打上 {@code @LlmLoop("name")} 即可，无须触碰框架任何其它部分。</p>
 */
public interface AgentLoop {

    /** Loop 名，供 {@code @LlmAgent(loop = "...")} 与配置引用。 */
    String name();

    /** 执行一次完整任务，返回最终结果。 */
    LoopResult run(LoopContext context);

    /** 人类可读的说明，会出现在启动日志与 {@code /llm/loops} 端点。 */
    default String description() {
        return "";
    }
}