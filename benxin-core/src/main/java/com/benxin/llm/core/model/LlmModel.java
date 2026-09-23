package com.benxin.llm.core.model;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;

/**
 * 模型抽象 —— 一切可替换的第一现场。
 *
 * <p>想接入未被内置支持的厂商，实现本接口并交给容器即可；
 * 想让模型带上缓存、限流、路由、降级、录制回放，也只需再包一层装饰器。
 * 本心自带的 {@code RetryingLlmModel} 就是这种装饰器的示范。</p>
 */
public interface LlmModel {

    /** 模型注册名。 */
    String name();

    /** 同步调用。 */
    ChatResponse chat(ChatRequest request);

    /**
     * 流式调用。默认实现退化为"同步调用 + 一次性回调"，
     * 因此任何只实现了 {@link #chat} 的模型都能立即享受流式接口。
     */
    default void stream(ChatRequest request, LlmStreamHandler handler) {
        handler.onStart();
        ChatResponse response = chat(request);
        String text = response.text();
        if (text != null && !text.isEmpty()) {
            handler.onTextDelta(text);
        }
        String thinking = response.message() == null ? "" : response.message().thinking();
        if (!thinking.isEmpty()) {
            handler.onThinkingDelta(thinking);
        }
        response.message().toolUses().forEach(handler::onToolCall);
        handler.onUsage(response.usage());
        handler.onComplete(response);
    }

    default ModelCapabilities capabilities() {
        return ModelCapabilities.DEFAULT;
    }

    /** 便于装饰器取回被包装的模型。 */
    default LlmModel unwrap() {
        return this;
    }
}