package com.benxin.llm.core.protocol;

/**
 * 一条 SSE 事件。
 *
 * @param event 事件名（{@code event:} 行），OpenAI/Gemini 常为空
 * @param data  数据（{@code data:} 行，多行以 \n 拼接）
 * @param id    事件 id
 */
public record SseEvent(String event, String data, String id) {

    public boolean isDone() {
        return "[DONE]".equals(data == null ? null : data.trim());
    }

    public boolean isBlank() {
        return data == null || data.isBlank();
    }
}