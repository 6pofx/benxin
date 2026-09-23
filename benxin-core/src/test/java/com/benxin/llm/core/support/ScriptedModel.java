package com.benxin.llm.core.support;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 脚本化的假模型：按预设顺序吐出响应，并记录收到的每一个请求。
 *
 * <p>它让"Loop 的行为"变成可断言的对象 —— 走了几步、每步发出什么请求、
 * 有没有把工具结果正确回灌，全都能在测试里精确验证，而不需要任何网络。</p>
 */
public final class ScriptedModel implements LlmModel {

    private final String name;
    private final Deque<ChatResponse> script;
    private final List<ChatRequest> requests = new ArrayList<>();
    private final ChatResponse fallback;
    private boolean streaming = true;
    private int streamChunkSize = 1000;

    private ScriptedModel(String name, List<ChatResponse> responses, ChatResponse fallback) {
        this.name = name;
        this.script = new ArrayDeque<>(responses);
        this.fallback = fallback;
    }

    public static ScriptedModel of(ChatResponse... responses) {
        return new ScriptedModel("scripted", List.of(responses), text("（脚本已用尽）"));
    }

    public static ScriptedModel of(List<ChatResponse> responses) {
        return new ScriptedModel("scripted", responses, text("（脚本已用尽）"));
    }

    /** 脚本用尽后反复返回同一个响应。 */
    public static ScriptedModel repeating(ChatResponse response, int times) {
        List<ChatResponse> list = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            list.add(response);
        }
        return new ScriptedModel("scripted", list, response);
    }

    /** 脚本用尽后始终返回"完成"，避免测试因模型沉默而死循环。 */
    public ScriptedModel withStreaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public ScriptedModel withStreamChunkSize(int size) {
        this.streamChunkSize = Math.max(1, size);
        return this;
    }

    // ---------- 脚本构造 ----------

    public static ChatResponse text(String content) {
        return ChatResponse.builder()
                .message(ChatMessage.assistant(content))
                .finishReason(FinishReason.STOP)
                .usage(new Usage(10, 5))
                .build();
    }

    public static ChatResponse toolCall(String toolName, String argumentsJson) {
        return toolCall("call_" + toolName, toolName, argumentsJson);
    }

    public static ChatResponse toolCall(String id, String toolName, String argumentsJson) {
        return ChatResponse.builder()
                .message(ChatMessage.assistant("", List.of(new ToolUsePart(id, toolName, argumentsJson))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(new Usage(10, 5))
                .build();
    }

    public static ChatResponse toolCalls(ToolUsePart... parts) {
        return ChatResponse.builder()
                .message(ChatMessage.assistant("", List.of(parts)))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(new Usage(10, 5))
                .build();
    }

    // ---------- LlmModel ----------

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.builder().streaming(streaming).toolCalling(true).build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requests.add(request);
        ChatResponse next = script.poll();
        return next == null ? fallback : next;
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        handler.onStart();
        ChatResponse response = chat(request);
        String content = response.message() == null ? "" : response.message().text();
        for (int i = 0; i < content.length(); i += streamChunkSize) {
            handler.onTextDelta(content.substring(i, Math.min(content.length(), i + streamChunkSize)));
        }
        if (response.message() != null) {
            response.message().toolUses().forEach(handler::onToolCall);
        }
        handler.onUsage(response.usage());
        handler.onComplete(response);
    }

    // ---------- 断言辅助 ----------

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public ChatRequest lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    public int callCount() {
        return requests.size();
    }

    /** 最后一次请求里是否包含某个工具的结果（用于验证工具结果是否被回灌）。 */
    public boolean lastRequestContainsToolResult(String toolName) {
        ChatRequest last = lastRequest();
        return last != null && last.messages().stream()
                .anyMatch(m -> m.role() == com.benxin.llm.core.message.Role.TOOL && toolName.equals(m.name()));
    }
}