package com.benxin.llm.core.agent;

/**
 * 本心内部使用的运行属性键。
 *
 * <p>这些键会出现在 {@code AgentInvocation#attributes()}、{@code ToolContext#attributes()}
 * 以及 {@code AgentResult#attributes()} 里，因此集中定义避免各处硬编码字符串。</p>
 */
public final class AgentAttributes {

    /** 本次调用的系统提示词覆盖值（声明式接口的方法级 {@code @SystemPrompt} 走这里）。 */
    public static final String SYSTEM_PROMPT = "benxin.systemPrompt";

    /** 待办清单，由 todo_write 工具维护。 */
    public static final String TODOS = "benxin.todos";

    /** 计划步骤，由 plan-execute / codex 循环维护。 */
    public static final String PLAN = "benxin.plan";

    /** 未显式指定子代理名时的默认子代理。 */
    public static final String DEFAULT_SUB_AGENT = "benxin.default-subagent";

    /** 会话 id。 */
    public static final String SESSION_ID = "benxin.sessionId";

    /** Agent 名称。 */
    public static final String AGENT_NAME = "benxin.agentName";

    private AgentAttributes() {
    }
}