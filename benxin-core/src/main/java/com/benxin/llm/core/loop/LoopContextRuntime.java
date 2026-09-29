package com.benxin.llm.core.loop;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRuntime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 {@link WorkflowRuntime} 接到 {@link LoopContext} 上。
 *
 * <p>这是整个工作流模式里唯一的"框架耦合点"，而且它只有四个方法。
 * 好处是 {@code core/workflow} 那个包（领域模型 + 引擎）完全不知道 Agent、拦截器、沙箱的存在，
 * 因此可以独立测试、独立复用；而工作流的每一次模型调用与工具调用又都<b>照常</b>经过
 * 沙箱校验、审批、拦截器改写、超时截断、流式转发与用量统计 —— 因为这里调用的就是其它 Loop
 * 用的同一批 {@code ctx} 方法。<b>工作流模式是既有能力的一次重新编排，不是一条绕过安全模型的旁路。</b></p>
 *
 * <p><b>一个 agent 节点 = 一次完整的子任务</b>：{@link #callAgent} 会一直推进到
 * "模型不再请求工具"为止。这与 {@code dsh-minimal} 等 Loop 的"一次往返 = 一步"不同，
 * 是有意为之 —— 图的节点是任务分解的单位，如果每个节点只允许一次裸模型往返，
 * "用工作流编排"就退化成了"手写 ReAct 的每一轮"，没人会愿意这么写。</p>
 */
public final class LoopContextRuntime implements WorkflowRuntime {

    private final LoopContext ctx;

    public LoopContextRuntime(LoopContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String callAgent(String prompt, WorkflowNode node) {
        ctx.append(ChatMessage.user(prompt));
        int nodeLimit = node.maxSteps() > 0 ? node.maxSteps() : Integer.MAX_VALUE;
        int calls = 0;

        while (calls < nodeLimit) {
            if (outOfBudget()) {
                // 全局预算见底：不再多要一次模型调用，直接用已有的最后一句话收场
                return lastAssistantText();
            }
            ChatResponse response = ctx.callModel();
            calls++;
            if (response.message() == null) {
                return "";
            }
            List<ToolUsePart> toolUses = response.message().toolUses();
            if (toolUses.isEmpty()) {
                return response.message().text();
            }

            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);

            Optional<String> direct = directAnswer(toolUses, results);
            if (direct.isPresent()) {
                return direct.get();
            }
        }

        // 节点级工具轮次用尽：给它一次"收口"的机会，否则节点输出会是空的，
        // 后续依赖 ${本节点} 的提示词就会变残缺。
        if (!outOfBudget()) {
            ctx.append(ChatMessage.user("本步骤的工具调用次数已达上限，请直接根据已有信息给出结论，不要再调用工具。"));
            ChatResponse wrapUp = ctx.callModel();
            if (wrapUp.message() != null) {
                return wrapUp.message().text();
            }
        }
        return lastAssistantText();
    }

    @Override
    public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
        return ctx.callTool(tool, args).content();
    }

    @Override
    public void emit(String type, Object payload) {
        ctx.emit(type, payload);
    }

    @Override
    public boolean outOfBudget() {
        return ctx.step() >= ctx.maxSteps();
    }

    /** 工具声明了 {@code returnDirect} 时，其结果直接作为节点输出（与其它 Loop 的语义保持一致）。 */
    private Optional<String> directAnswer(List<ToolUsePart> toolUses, List<ChatMessage> results) {
        for (int i = 0; i < toolUses.size() && i < results.size(); i++) {
            boolean direct = ctx.tools().find(toolUses.get(i).name())
                    .map(ToolCallback::returnDirect)
                    .orElse(Boolean.FALSE);
            if (direct) {
                return Optional.of(results.get(i).text());
            }
        }
        return Optional.empty();
    }

    private String lastAssistantText() {
        List<ChatMessage> messages = ctx.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == Role.ASSISTANT && !message.text().isBlank()) {
                return message.text();
            }
        }
        return "";
    }
}
