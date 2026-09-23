package com.benxin.llm.core.chat;

/** 统一的结束原因。 */
public enum FinishReason {
    /** 模型正常说完 */
    STOP,
    /** 触达 max_tokens */
    LENGTH,
    /** 模型要求调用工具 */
    TOOL_CALLS,
    /** 被内容安全策略拦截 */
    CONTENT_FILTER,
    /** 出错 */
    ERROR,
    UNKNOWN;

    /** 把三家协议五花八门的 stop_reason 归一化。 */
    public static FinishReason fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase()) {
            case "stop", "end_turn", "stop_sequence", "complete" -> STOP;
            case "length", "max_tokens", "max_output_tokens" -> LENGTH;
            case "tool_calls", "tool_use", "function_call" -> TOOL_CALLS;
            case "content_filter", "safety", "recitation", "blocklist", "prohibited_content" -> CONTENT_FILTER;
            case "error", "malformed_function_call" -> ERROR;
            default -> UNKNOWN;
        };
    }
}