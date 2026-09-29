package com.benxin.llm.core.workflow;

import java.util.Map;

/**
 * 工作流执行时需要的全部副作用。
 *
 * <p>把"节点要干活"这件事抽成一个接口，而不是让 {@link WorkflowEngine} 直接依赖
 * {@code LoopContext}，换来两个好处：</p>
 * <ol>
 *   <li>引擎可以用一个假运行时做纯逻辑单测 —— 分支、循环、重试、预算耗尽这些最容易出错的
 *       地方，验证时不需要模型、不需要网络；</li>
 *   <li>同一个引擎因此也能脱离 Agent 使用（例如把工作流跑在一个 CLI 或任务队列里）。</li>
 * </ol>
 *
 * <p>唯一的实现是 {@code core/loop} 包里的 {@code LoopContextRuntime}，它把调用转发给 {@code LoopContext}，
 * 于是沙箱、审批、拦截器、流式与用量统计全部照旧生效 —— 工作流模式不是旁路，
 * 而是把既有能力重新编排了一遍。</p>
 */
public interface WorkflowRuntime {

    /**
     * 让模型完成一个 agent 节点：内部会一直推进到"模型不再请求工具"为止，
     * 因此一个节点就是一次完整的子任务，而不是一次裸的模型往返。
     *
     * @param prompt 已渲染好的提示词
     * @param node   当前节点（实现可用于读取 {@code maxSteps} 等配置与打点）
     * @return 模型给出的文本（可能为空串）
     */
    String callAgent(String prompt, WorkflowNode node);

    /**
     * 直接调用一个工具，不经过模型。
     *
     * @param args 已渲染好的参数
     */
    String callTool(String tool, Map<String, Object> args, WorkflowNode node);

    /** 广播一个事件（会转给 {@code AgentListener.onCustomEvent}）。 */
    void emit(String type, Object payload);

    /** 是否已用完步数预算；引擎在每次推进节点前检查。 */
    boolean outOfBudget();
}
