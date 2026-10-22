package com.benxin.llm.core.loop;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.core.workflow.ChoiceContract;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandlerRegistry;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRuntime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 {@link WorkflowRuntime} 接到 {@link LoopContext} 上。
 *
 * <p>这是整个工作流模式里唯一的"框架耦合点"，而且它只有五个方法。
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
    private final WorkflowHandlerRegistry handlers;
    /** 输出被 max_tokens 截断的节点（按发生顺序；同一节点多次截断就记多次）。 */
    private final List<String> truncatedNodes = new java.util.ArrayList<>();

    public LoopContextRuntime(LoopContext ctx) {
        this(ctx, WorkflowHandlerRegistry.empty());
    }

    /**
     * @param handlers Java 节点的处理器注册表；图里没有 java 节点时传空表即可
     */
    public LoopContextRuntime(LoopContext ctx, WorkflowHandlerRegistry handlers) {
        this.ctx = ctx;
        this.handlers = handlers == null ? WorkflowHandlerRegistry.empty() : handlers;
    }

    @Override
    public String callAgent(String prompt, WorkflowNode node) {
        return callAgent(null, prompt, node);
    }

    @Override
    public String callAgent(String system, String prompt, WorkflowNode node) {
        ctx.append(ChatMessage.user(prompt));
        String text = runAgentTurns(system, node);

        List<String> choices = node.choice();
        if (choices.isEmpty()) {
            return text;
        }
        // 输出契约：归一化 → 判定 → 不合规就纠正重试 → 用尽则让节点失败。
        // 刻意把"判不出来"当成失败，而不是把模糊答案塞进图里让下游猜。
        for (int attempt = 0; ; attempt++) {
            String matched = ChoiceContract.match(text, choices);
            if (matched != null) {
                return matched;
            }
            if (attempt >= ChoiceContract.DEFAULT_RETRIES || outOfBudget()) {
                String why = attempt >= ChoiceContract.DEFAULT_RETRIES
                        ? "已重试 " + attempt + " 次"
                        : "步数预算已耗尽，无法纠正重试";
                throw new IllegalStateException("节点 [" + node.id() + "] 的输出不在契约 " + choices
                        + " 里（" + why + "）：" + Json.abbreviate(text));
            }
            ctx.emit("workflow.choice-retry", Map.of(
                    "node", node.id(),
                    "attempt", attempt + 1,
                    "allowed", choices,
                    "raw", Json.abbreviate(text)));
            ctx.append(ChatMessage.user(ChoiceContract.correction(choices)));
            text = runAgentTurns(system, node);
        }
    }

    /**
     * 推进到"模型不再请求工具"为止，返回模型最后一段文本。
     *
     * <p>抽出来是为了让输出契约的纠正重试能复用同一段推进逻辑 ——
     * 重试时不应该再追加一遍原始提示词（它已经在历史里了），只追加纠正消息。</p>
     */
    private String runAgentTurns(String system, WorkflowNode node) {
        int nodeLimit = node.maxSteps() > 0 ? node.maxSteps() : Integer.MAX_VALUE;
        int calls = 0;

        while (calls < nodeLimit) {
            if (outOfBudget()) {
                // 全局预算见底：不再多要一次模型调用，直接用已有的最后一句话收场
                return lastAssistantText();
            }
            ChatResponse response = callModel(system);
            calls++;
            noteTruncation(response, node);
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
            ChatResponse wrapUp = callModel(system);
            noteTruncation(wrapUp, node);
            if (wrapUp.message() != null) {
                return wrapUp.message().text();
            }
        }
        return lastAssistantText();
    }

    /**
     * 记录"这个节点的这次响应被 {@code max_tokens} 截断"。
     *
     * <p>节点级的信号以前是彻底丢掉的：{@code WorkflowRun} 只留最后一次响应的结束原因，
     * 于是"第 2 个节点被截断、第 4 个节点正常说完"这种图，事后完全看不出中间出过事。</p>
     */
    private void noteTruncation(ChatResponse response, WorkflowNode node) {
        if (response == null || response.finishReason() != FinishReason.LENGTH) {
            return;
        }
        truncatedNodes.add(node.id());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("workflow", ctx.agentName());
        payload.put("node", node.id());
        payload.put("step", ctx.step());
        payload.put("textEmpty", response.message() == null || response.message().text().isBlank());
        emit("workflow.node-truncated", payload);
    }

    @Override
    public List<String> truncatedNodes() {
        return List.copyOf(truncatedNodes);
    }

    @Override
    public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
        ToolResult result = ctx.callTool(tool, args);
        if (result.error()) {
            // 工具失败（未知工具名 / 被沙箱拒绝 / 工具抛异常 / 超时）必须升级成"节点级失败"，
            // 否则 tool 节点的 retry 与 continueOnError 永远没有东西可接 ——
            // 一个写错工具名的节点会把 "未知工具 […]" 当成正常输出静默地流到下游，
            // 直到最终答案很奇怪才被发现。
            throw new IllegalStateException(result.content());
        }
        return result.content();
    }

    @Override
    public NodeOutput callHandler(String handler, NodeInput input, WorkflowNode node) {
        // 注册表是唯一的解析依据：找不到就抛，由引擎转成带节点 id 的失败，
        // 而不是静默返回空串让下游去猜。处理器自己抛的异常同样原样上抛，
        // 这样节点的 retry / continueOnError 对 java 节点与其它节点完全一致。
        return handlers.require(handler).handle(input);
    }

    /**
     * 节点级系统提示词只有非空时才走覆盖通道。
     *
     * <p>空串时仍然调用无参版本 —— 不是省事，而是为了让"没有节点级系统提示词"这条路径
     * 与其它 Loop 完全一致（自定义 {@code LoopContext} 不覆盖该方法时也不会被影响）。</p>
     */
    private ChatResponse callModel(String system) {
        return system == null || system.isBlank() ? ctx.callModel() : ctx.callModel(system);
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
