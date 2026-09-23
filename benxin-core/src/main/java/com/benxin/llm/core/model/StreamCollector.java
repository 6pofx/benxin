package com.benxin.llm.core.model;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolUsePart;

import java.util.ArrayList;
import java.util.List;

/**
 * 把流式回调重新聚合成一个完整的 {@link ChatResponse}，
 * 同时把事件透传给下游监听者（用于前端实时展示）。
 *
 * <p>Agent Loop 统一使用它：既拿到完整响应做决策，又能实时把增量推给用户。</p>
 */
public final class StreamCollector implements LlmStreamHandler {

    private final LlmStreamHandler downstream;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<ToolUsePart> toolUses = new ArrayList<>();
    private Usage usage = Usage.ZERO;
    private ChatResponse completed;
    private Throwable error;
    private boolean started;

    public StreamCollector() {
        this(null);
    }

    public StreamCollector(LlmStreamHandler downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onStart() {
        started = true;
        if (downstream != null) {
            downstream.onStart();
        }
    }

    @Override
    public void onTextDelta(String delta) {
        if (delta != null) {
            text.append(delta);
        }
        if (downstream != null) {
            downstream.onTextDelta(delta);
        }
    }

    @Override
    public void onThinkingDelta(String delta) {
        if (delta != null) {
            thinking.append(delta);
        }
        if (downstream != null) {
            downstream.onThinkingDelta(delta);
        }
    }

    @Override
    public void onToolCall(ToolUsePart toolUse) {
        toolUses.add(toolUse);
        if (downstream != null) {
            downstream.onToolCall(toolUse);
        }
    }

    @Override
    public void onUsage(Usage value) {
        if (value != null) {
            this.usage = value.inputTokens() == 0 && value.outputTokens() == 0
                    ? this.usage : this.usage.plus(value);
        }
        if (downstream != null) {
            downstream.onUsage(value);
        }
    }

    @Override
    public void onComplete(ChatResponse response) {
        this.completed = response;
        if (downstream != null) {
            downstream.onComplete(response);
        }
    }

    @Override
    public void onError(Throwable cause) {
        this.error = cause;
        if (downstream != null) {
            downstream.onError(cause);
        }
    }

    /** 返回聚合后的响应；若模型未回调 onComplete，则用收集到的增量自行合成。 */
    public ChatResponse response() {
        if (completed != null) {
            return completed;
        }
        List<ContentPart> parts = new ArrayList<>();
        if (!thinking.isEmpty()) {
            parts.add(new ThinkingPart(thinking.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(toolUses);
        FinishReason reason = toolUses.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        ChatMessage message = ChatMessage.assistant(parts);
        return ChatResponse.builder()
                .message(message)
                .finishReason(reason)
                .usage(usage)
                .build();
    }

    public String text() {
        return text.toString();
    }

    public String thinkingText() {
        return thinking.toString();
    }

    public List<ToolUsePart> toolUses() {
        return List.copyOf(toolUses);
    }

    public Usage usage() {
        return usage;
    }

    public Throwable error() {
        return error;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean hasContent() {
        return !text.isEmpty() || !thinking.isEmpty() || !toolUses.isEmpty();
    }
}