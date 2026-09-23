package com.benxin.llm.core.model;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ToolUsePart;

/**
 * 流式事件回调。所有方法都有默认空实现，使用方只覆写关心的那几个。
 *
 * <p>工具调用只在参数拼装完整后才回调一次 {@link #onToolCall}，
 * 增量拼装的复杂度由协议解码器吸收，不泄漏给上层。</p>
 */
public interface LlmStreamHandler {

    default void onStart() {
    }

    default void onTextDelta(String delta) {
    }

    default void onThinkingDelta(String delta) {
    }

    default void onToolCall(ToolUsePart toolUse) {
    }

    default void onUsage(Usage usage) {
    }

    default void onComplete(ChatResponse response) {
    }

    default void onError(Throwable error) {
    }

    /** 一个只转发文本增量的便捷实现。 */
    static LlmStreamHandler ofText(java.util.function.Consumer<String> consumer) {
        return new LlmStreamHandler() {
            @Override
            public void onTextDelta(String delta) {
                consumer.accept(delta);
            }
        };
    }
}