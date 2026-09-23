package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.agent.DefaultAgent;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolCallRecord;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loop 运行时上下文的标准实现。
 *
 * <p>它是整个插件里"最容易写错、所以集中写一次"的地方：模型调用要串上上下文管理、
 * 拦截器改写、流式聚合与用量累计；工具执行要串上沙箱、审批、拦截器、超时、截断与事件广播。
 * 把这些都做进上下文之后，写一个新的 Agent Loop 就只剩下纯逻辑。</p>
 */
public class DefaultLoopContext implements LoopContext, ToolContext {

    private final DefaultAgent agent;
    private final AgentInvocation invocation;
    private final List<ChatMessage> messages;
    private final String systemPrompt;
    private final LlmStreamHandler streamHandler;
    private final List<AgentInterceptor> interceptors;
    private final AtomicInteger step = new AtomicInteger();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();
    private final Map<String, Object> attributes;
    private final Object toolLock = new Object();

    private Usage usage = Usage.ZERO;
    private String lastText = "";
    private FinishReason finishReason = FinishReason.STOP;
    private boolean maxStepsReached;

    public DefaultLoopContext(DefaultAgent agent, AgentInvocation invocation, List<ChatMessage> messages,
                              String systemPrompt, LlmStreamHandler streamHandler,
                              List<AgentInterceptor> interceptors) {
        this.agent = agent;
        this.invocation = invocation;
        this.messages = messages;
        this.systemPrompt = systemPrompt;
        this.streamHandler = streamHandler;
        this.interceptors = interceptors == null ? List.of() : interceptors;
        this.attributes = invocation.attributes();
    }

    // ---------- 身份 ----------

    @Override
    public String agentName() {
        return agent.name();
    }

    @Override
    public String sessionId() {
        return invocation.sessionId();
    }

    @Override
    public AgentSpec spec() {
        return agent.spec();
    }

    @Override
    public int step() {
        return step.get();
    }

    @Override
    public int maxSteps() {
        return agent.spec().maxSteps();
    }

    // ---------- 协作者 ----------

    @Override
    public LlmModel model() {
        return agent.model();
    }

    @Override
    public ToolRegistry tools() {
        return agent.tools();
    }

    @Override
    public ContextManager contextManager() {
        return agent.contextManager();
    }

    @Override
    public SystemPromptProvider systemPromptProvider() {
        return agent.systemPromptProvider();
    }

    @Override
    public ToolSandbox sandbox() {
        return agent.sandbox();
    }

    @Override
    public ApprovalHandler approvalHandler() {
        return agent.approvalHandler();
    }

    @Override
    public ApprovalPolicy approvalPolicy() {
        return agent.approvalPolicy();
    }

    @Override
    public List<AgentInterceptor> interceptors() {
        return interceptors;
    }

    @Override
    public AgentListener listener() {
        return agent.listener();
    }

    @Override
    public LlmStreamHandler streamHandler() {
        return streamHandler;
    }

    // ---------- 状态 ----------

    @Override
    public List<ChatMessage> messages() {
        return messages;
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    // ---------- 模型调用 ----------

    @Override
    public ChatResponse callModel() {
        ChatResponse response = callModel(messages);
        append(response.message());
        return response;
    }

    @Override
    public ChatResponse callModel(List<ChatMessage> history) {
        int current = step.incrementAndGet();
        AgentListener listener = listener();
        listener.onStepStart(agentName(), current);

        ChatRequest request = ChatRequest.builder()
                .model(spec().model())
                .messages(contextManager().prepare(systemPrompt, history, model()))
                .tools(tools().specs())
                .temperature(spec().temperature() < 0 ? null : spec().temperature())
                .maxTokens(spec().maxTokens() < 0 ? null : spec().maxTokens())
                .build();
        for (AgentInterceptor interceptor : interceptors) {
            ChatRequest rewritten = interceptor.beforeModel(invocation, request);
            if (rewritten != null) {
                request = rewritten;
            }
        }

        StreamCollector collector = new StreamCollector(new LlmStreamHandler() {
            @Override
            public void onStart() {
                // 必须转发：调用方常以 onStart 作为"可以开始渲染了"的信号，
                // 漏掉它会让流式界面一直停在加载态。
                if (streamHandler != null) {
                    streamHandler.onStart();
                }
            }

            @Override
            public void onTextDelta(String delta) {
                listener.onTextDelta(delta);
                if (streamHandler != null) {
                    streamHandler.onTextDelta(delta);
                }
            }

            @Override
            public void onThinkingDelta(String delta) {
                listener.onThinkingDelta(delta);
                if (streamHandler != null) {
                    streamHandler.onThinkingDelta(delta);
                }
            }

            @Override
            public void onToolCall(ToolUsePart toolUse) {
                if (streamHandler != null) {
                    streamHandler.onToolCall(toolUse);
                }
            }

            @Override
            public void onUsage(Usage value) {
                if (streamHandler != null) {
                    streamHandler.onUsage(value);
                }
            }

            @Override
            public void onComplete(ChatResponse response) {
                if (streamHandler != null) {
                    streamHandler.onComplete(response);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (streamHandler != null) {
                    streamHandler.onError(error);
                }
            }
        });

        model().stream(request, collector);
        if (collector.error() != null) {
            Throwable cause = collector.error();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new com.benxin.llm.core.model.ModelException("模型流式调用失败", cause);
        }

        ChatResponse response = collector.response();
        for (AgentInterceptor interceptor : interceptors) {
            ChatResponse rewritten = interceptor.afterModel(invocation, response);
            if (rewritten != null) {
                response = rewritten;
            }
        }

        usage = usage.plus(response.usage());
        finishReason = response.finishReason();
        if (response.message() != null) {
            lastText = response.message().text();
        }
        listener.onStepEnd(agentName(), current, response);
        contextManager().afterStep(history, model());
        return response;
    }

    @Override
    public void append(ChatMessage message) {
        if (message != null) {
            messages.add(message);
        }
    }

    // ---------- 工具执行 ----------

    @Override
    public List<ChatMessage> executeTools(List<ToolUsePart> toolUses) {
        if (toolUses == null || toolUses.isEmpty()) {
            return List.of();
        }
        for (ToolUsePart toolUse : toolUses) {
            listener().onToolCall(ToolInvocation.of(toolUse, agentName(), sessionId(), step.get()));
        }

        boolean parallel = toolUses.size() > 1
                && toolUses.stream().allMatch(u -> tools().find(u.name())
                        .map(ToolCallback::parallelSafe).orElse(Boolean.FALSE));

        // 并行执行时完成顺序是不确定的，但"模型按顺序请求了哪些工具"必须可复现：
        // 因此这里先按声明顺序收齐结果，再统一写入 results 与审计轨迹。
        List<ToolOutcome> outcomes = new ArrayList<>(toolUses.size());
        if (parallel) {
            List<CompletableFuture<ToolOutcome>> futures = toolUses.stream()
                    .map(u -> CompletableFuture.supplyAsync(() -> executeOne(u), agent.toolExecutor()))
                    .toList();
            for (CompletableFuture<ToolOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new com.benxin.llm.core.model.ModelException("工具并行执行被中断", e);
                } catch (ExecutionException e) {
                    throw wrap(e.getCause());
                }
            }
        } else {
            for (ToolUsePart toolUse : toolUses) {
                outcomes.add(executeOne(toolUse));
            }
        }

        List<ChatMessage> results = new ArrayList<>(outcomes.size());
        for (ToolOutcome outcome : outcomes) {
            results.add(outcome.message());
            synchronized (toolLock) {
                toolCalls.add(outcome.record());
            }
        }
        return results;
    }

    @Override
    public ToolResult callTool(String name, Map<String, Object> arguments) {
        ToolUsePart part = new ToolUsePart("direct-" + System.nanoTime(), name,
                com.benxin.llm.core.util.Json.writeQuietly(arguments == null ? Map.of() : arguments));
        ToolOutcome outcome = executeOne(part);
        synchronized (toolLock) {
            toolCalls.add(outcome.record());
        }
        ChatMessage message = outcome.message();
        boolean error = message.parts().stream()
                .filter(com.benxin.llm.core.message.ToolResultPart.class::isInstance)
                .map(com.benxin.llm.core.message.ToolResultPart.class::cast)
                .findFirst()
                .map(com.benxin.llm.core.message.ToolResultPart::error)
                .orElse(false);
        return new ToolResult(message.text(), error, Map.of());
    }

    /** 一次工具执行的产物：回灌给模型的消息 + 进审计轨迹的记录，两者必须成对出现。 */
    private record ToolOutcome(ChatMessage message, ToolCallRecord record) {
    }

    private ToolOutcome executeOne(ToolUsePart toolUse) {
        ToolInvocation call = ToolInvocation.of(toolUse, agentName(), sessionId(), step.get());
        AgentListener listener = listener();

        Optional<ToolCallback> found = tools().find(call.toolName());
        if (found.isEmpty()) {
            ToolResult unknown = ToolResult.error("未知工具 [" + call.toolName() + "]，可用工具: " + tools().names());
            listener.onToolResult(call, unknown);
            // 未知工具同样是"模型做过的一次尝试"，必须进审计轨迹 ——
            // 否则排查"模型为什么老是不干活"时会看不到它反复调用一个不存在的工具。
            return new ToolOutcome(toolMessage(call, unknown), ToolCallRecord.of(call, unknown, 0L));
        }
        ToolCallback callback = found.get();

        ToolResult result;
        long start = System.nanoTime();

        // 1. 沙箱守门
        ToolSandbox.Decision decision = sandbox().check(call);
        if (decision.denied() && approvalPolicy() == ApprovalPolicy.ON_FAILURE) {
            boolean approved = approvalHandler().approve(new ApprovalHandler.ApprovalRequest(
                    call.toolName(), call.arguments(), "沙箱拒绝：" + decision.reason(), agentName(), sessionId()));
            if (approved) {
                decision = ToolSandbox.Decision.allow();
            }
        }
        if (decision.denied()) {
            result = decision.toToolResult();
        } else if (needsApproval(call, callback)
                && !approvalHandler().approve(new ApprovalHandler.ApprovalRequest(
                        call.toolName(), call.arguments(), callback.description(), agentName(), sessionId()))) {
            result = ToolResult.error("用户拒绝执行工具 [" + call.toolName() + "]");
        } else {
            // 2. 拦截器短路
            result = null;
            for (AgentInterceptor interceptor : interceptors) {
                ToolResult shortCircuit = interceptor.beforeTool(call);
                if (shortCircuit != null) {
                    result = shortCircuit;
                    break;
                }
            }
            // 3. 真正执行（带超时）
            if (result == null) {
                result = executeWithTimeout(callback, call);
            }
        }

        long duration = (System.nanoTime() - start) / 1_000_000;
        result = new ToolResult(sandbox().truncate(result.content()), result.error(), result.meta());

        for (AgentInterceptor interceptor : interceptors) {
            interceptor.afterTool(call, result);
        }
        listener.onToolResult(call, result);
        return new ToolOutcome(toolMessage(call, result), ToolCallRecord.of(call, result, duration));
    }

    private ChatMessage toolMessage(ToolInvocation call, ToolResult result) {
        return ChatMessage.toolResult(call.toolUseId(), call.toolName(), result.content(), result.error());
    }

    private ToolResult executeWithTimeout(ToolCallback callback, ToolInvocation call) {
        long timeoutMillis = Math.max(1, sandbox().timeout().toMillis());
        AtomicReference<ToolResult> holder = new AtomicReference<>();
        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> holder.set(callback.call(call.arguments(), this)), agent.toolExecutor());
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.error("工具 [" + call.toolName() + "] 执行超时（>" + timeoutMillis + "ms）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("工具 [" + call.toolName() + "] 执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.error("工具 [" + call.toolName() + "] 抛出异常: " + cause);
        }
        ToolResult result = holder.get();
        return result == null ? ToolResult.error("工具 [" + call.toolName() + "] 未返回结果") : result;
    }

    private boolean needsApproval(ToolInvocation call, ToolCallback callback) {
        return switch (approvalPolicy()) {
            case NEVER -> false;
            case ALWAYS -> true;
            case ON_REQUEST -> callback.requiresApproval();
            case ON_FAILURE -> false;
        };
    }

    private static RuntimeException wrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new com.benxin.llm.core.model.ModelException("工具执行失败", cause);
    }

    // ---------- 子代理 ----------

    @Override
    public boolean supportsSubAgents() {
        return !spec().subAgents().isEmpty();
    }

    @Override
    public AgentResult spawnSubAgent(String subAgentName, String prompt, Map<String, Object> extra) {
        AgentResult result = agent.spawn(subAgentName, prompt, extra, listener(), agentName());
        synchronized (toolLock) {
            attributes.put("lastSubAgent", subAgentName);
        }
        return result;
    }

    @Override
    public boolean canSpawnSubAgent() {
        return supportsSubAgents();
    }

    @Override
    public Optional<AgentResult> spawn(String name, String prompt, Map<String, Object> extra) {
        AgentResult result = agent.spawn(name, prompt, extra, listener(), agentName());
        synchronized (toolLock) {
            attributes.put("lastSubAgent", name);
        }
        return Optional.of(result);
    }

    // ---------- 事件 ----------

    @Override
    public void emit(String type, Object payload) {
        listener().onCustomEvent(type, payload);
    }

    // ---------- 收尾 ----------

    /** 标记因步数上限而中断。 */
    public void markMaxStepsReached() {
        this.maxStepsReached = true;
    }

    /** 累计用量（子代理等场景由 Loop 手动补充）。 */
    public void addUsage(Usage extra) {
        if (extra != null) {
            this.usage = this.usage.plus(extra);
        }
    }

    /** 组装 Loop 结果。 */
    public LoopResult toResult(String text) {
        String finalText = text != null ? text : lastText;
        return LoopResult.builder()
                .loopName(agent.loop().name())
                .text(finalText)
                .finishReason(finishReason)
                .messages(Collections.unmodifiableList(new ArrayList<>(messages)))
                .usage(usage)
                .steps(step.get())
                .toolCalls(toolCalls)
                .maxStepsReached(maxStepsReached)
                .attributes(new LinkedHashMap<>(attributes))
                .build();
    }

    /** 便捷：把一段文本作为最终答案直接产出。 */
    public LoopResult finish() {
        return toResult(lastText);
    }

    public Usage usage() {
        return usage;
    }

    public List<ToolCallRecord> toolCallRecords() {
        return List.copyOf(toolCalls);
    }
}