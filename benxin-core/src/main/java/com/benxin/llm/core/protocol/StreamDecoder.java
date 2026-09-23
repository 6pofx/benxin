package com.benxin.llm.core.protocol;

/**
 * 协议流式解码器：把一条条 SSE 事件翻译成统一的 {@code LlmStreamHandler} 回调。
 *
 * <p>实现方负责把增量片段（如 OpenAI 的 {@code arguments} 分片、
 * Anthropic 的 {@code input_json_delta}）拼装成完整的工具调用后再回调。</p>
 */
public interface StreamDecoder {

    /** 处理一条 SSE 事件。 */
    void accept(SseEvent event);

    /** 流结束时的冲刷与收尾（补发 onComplete）。 */
    void finish();

    /**
     * 流异常中断。
     *
     * <p>实现方可以在这里做内部状态清理，也可以把异常转发给 handler —— 传输层
     * 会对 {@code onError} 去重，因此两种做法都不会让订阅方收到两次错误。</p>
     */
    default void error(Throwable cause) {
    }
}