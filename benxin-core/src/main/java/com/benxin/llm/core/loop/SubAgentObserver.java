package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.AgentResult;

/**
 * 子代理派生的观察点：让 Loop 能感知"这一次运行里派生过哪些子代理"。
 *
 * <p><b>为什么需要一个专门的接口：</b>子代理的真实调用链是
 * {@code TaskTool → ToolContext.spawn → DefaultLoopContext.spawn → DefaultAgent.spawn}，
 * 它<b>不经过</b> Loop 的任何方法。于是像 claude-code 这样"想为子代理补记用量、发出自己的
 * {@code claude-code.subagent-start/end} 事件"的 Loop，只能自己写一个 {@code spawnTask()}
 * 备用入口 —— 而那个入口永远不会被框架调用，注释里的承诺全部落空。</p>
 *
 * <p>把这个观察点做成接口后，Loop 实现它即可拿到真实路径上的通知；用法量补记这类
 * 与 Loop 无关的部分则由 {@link DefaultLoopContext} 统一负责（所有 Loop 一起受益）。</p>
 */
public interface SubAgentObserver {

    /**
     * 即将派生子代理。
     *
     * @param ctx          父 Loop 上下文（可直接 {@code ctx.emit(...)}）
     * @param subAgentName 被派生的子代理名
     * @param prompt       交给子代理的完整指令
     */
    default void onSubAgentStart(LoopContext ctx, String subAgentName, String prompt) {
    }

    /**
     * 子代理返回。
     *
     * @param ctx          父 Loop 上下文
     * @param subAgentName 被派生的子代理名
     * @param result       子代理的运行结果（不为 null）
     */
    default void onSubAgentEnd(LoopContext ctx, String subAgentName, AgentResult result) {
    }
}
