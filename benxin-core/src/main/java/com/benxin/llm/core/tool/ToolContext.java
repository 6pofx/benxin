package com.benxin.llm.core.tool;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;

import java.util.Map;
import java.util.Optional;

/**
 * 工具执行上下文：工具能"看到"的运行时环境。
 *
 * <p>工具据此可以派生子代理、读会话属性、向监听器发事件，
 * 也可以访问沙箱与审批器做出更细粒度的自保判断。</p>
 */
public interface ToolContext {

    String agentName();

    String sessionId();

    /** 当前步数（从 1 开始）。 */
    int step();

    /** 本次运行的共享属性表，工具可读写。 */
    Map<String, Object> attributes();

    /** 当前 Agent 使用的模型，便于"工具内部再调一次模型"。 */
    LlmModel model();

    ToolSandbox sandbox();

    ApprovalHandler approvalHandler();

    AgentListener listener();

    /** 向监听器发一个自定义事件。 */
    void emit(String type, Object payload);

    /** 是否具备派生子代理的能力（取决于 Agent 是否配置了 subAgents）。 */
    default boolean canSpawnSubAgent() {
        return false;
    }

    /**
     * 派生一个子代理跑独立任务并拿到结果。
     *
     * <p>方法名刻意不叫 {@code spawnSubAgent}：{@code LoopContext} 里已有同名方法但返回
     * {@link AgentResult} 而非 {@link Optional}，两者会被擦除成同一签名而产生冲突。</p>
     *
     * @return 不具备子代理能力时返回空
     */
    default Optional<AgentResult> spawn(String agentName, String prompt, Map<String, Object> attributes) {
        return Optional.empty();
    }
}