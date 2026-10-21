package com.benxin.llm.core.agent;

/**
 * 本心内部使用的运行属性键。
 *
 * <p>这些键会出现在 {@code AgentInvocation#attributes()}、{@code ToolContext#attributes()}
 * 以及 {@code AgentResult#attributes()} 里，因此集中定义避免各处硬编码字符串。</p>
 *
 * <p>取值口径：{@link #SYSTEM_PROMPT} / {@link #TODOS} / {@link #PLAN} /
 * {@link #DEFAULT_SUB_AGENT} 由对应的组件写入；{@link #SESSION_ID} 与 {@link #AGENT_NAME}
 * 由 {@code DefaultAgent.run} 在运行开始时播种，因此一次运行内所有工具/拦截器都读得到；
 * {@link #TRUNCATED} / {@link #TRUNCATED_STEPS} 由 {@code DefaultLoopContext} 在
 * 遇到 {@code finishReason == LENGTH} 时写入。</p>
 */
public final class AgentAttributes {

    /** 本次调用的系统提示词覆盖值（声明式接口的方法级 {@code @SystemPrompt} 走这里）。 */
    public static final String SYSTEM_PROMPT = "benxin.systemPrompt";

    /** 待办清单，由 todo_write 工具维护（{@code TodoWriteTool} 读写的就是这个键）。 */
    public static final String TODOS = "benxin.todos";

    /** 计划步骤，由 plan-execute / codex 循环维护。 */
    public static final String PLAN = "benxin.plan";

    /**
     * 未显式指定子代理名时的默认子代理（{@code TaskTool} 读取它）。
     *
     * <p>注意 {@code StagedLoop} 用的是另一个键 {@code benxin.staged.plan} —— 那是分阶段循环
     * 自己的计划表，与 {@link #PLAN} 不是同一份数据，别混用。</p>
     */
    public static final String DEFAULT_SUB_AGENT = "benxin.default-subagent";

    /** 会话 id；由 {@code DefaultAgent.run} 在运行开始时写入。 */
    public static final String SESSION_ID = "benxin.sessionId";

    /** Agent 名称；由 {@code DefaultAgent.run} 在运行开始时写入。 */
    public static final String AGENT_NAME = "benxin.agentName";

    /**
     * 本次运行是否出现过"输出被 max_tokens 截断"（{@code finishReason == LENGTH}）。
     *
     * <p>值为 {@link Boolean}；任意一步被截断过即为 {@code true}，步数明细见
     * {@link #TRUNCATED_STEPS}。为什么需要这个键：{@code AgentResult.finishReason()} 只保留
     * <b>最后一次</b>响应，中间步（工作流里就是中间节点）被截断会被后面正常的响应覆盖掉。</p>
     */
    public static final String TRUNCATED = "benxin.truncated";

    /**
     * 被 max_tokens 截断的步号列表（{@link java.util.List List&lt;Integer&gt;}，按发生顺序追加）。
     *
     * <p>思考型模型（reasoning）把输出预算花在思考上时，典型形态是
     * {@code finish_reason=length} + <b>正文空串</b>，只看最终答案完全看不出发生过什么。</p>
     */
    public static final String TRUNCATED_STEPS = "benxin.truncatedSteps";

    private AgentAttributes() {
    }
}