package com.benxin.llm.core.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 极简 SSE 解析器，同时兼容三种协议的流式输出：
 *
 * <ul>
 *   <li>OpenAI / 兼容网关：{@code data: {...}} 直至 {@code data: [DONE]}</li>
 *   <li>Anthropic：{@code event: content_block_delta} + {@code data: {...}} 成对出现</li>
 *   <li>Gemini：{@code alt=sse} 下的 {@code data: {...}}，无结束标记</li>
 * </ul>
 *
 * <p>按 SSE 规范处理多行 {@code data:}、注释行（{@code :} 开头的心跳）与 CRLF。</p>
 */
public final class SseParser {

    private SseParser() {
    }

    /** 从输入流逐事件解析并回调，直到流结束或遇到 {@code [DONE]}。 */
    public static void parse(InputStream in, Consumer<SseEvent> consumer) throws IOException {
        parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), consumer);
    }

    public static void parse(BufferedReader reader, Consumer<SseEvent> consumer) throws IOException {
        String event = null;
        String id = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                SseEvent flushed = flush(event, data, id);
                event = null;
                id = null;
                if (flushed != null) {
                    consumer.accept(flushed);
                    if (flushed.isDone()) {
                        return;
                    }
                }
                continue;
            }
            if (line.charAt(0) == ':') {
                // 心跳/注释行，忽略
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> event = value;
                case "data" -> {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                case "id" -> id = value;
                default -> {
                    // retry 等字段忽略
                }
            }
        }
        // 流结束时冲刷未以空行结尾的最后一个事件
        SseEvent last = flush(event, data, id);
        if (last != null) {
            consumer.accept(last);
        }
    }

    private static SseEvent flush(String event, StringBuilder data, String id) {
        if (data.isEmpty() && event == null) {
            return null;
        }
        SseEvent e = new SseEvent(event, data.toString(), id);
        data.setLength(0);
        return e;
    }

    /** 把一整个响应体按 SSE 解析（便于测试与一次性响应）。 */
    public static void parse(String body, Consumer<SseEvent> consumer) {
        try {
            parse(new BufferedReader(new java.io.StringReader(body)), consumer);
        } catch (IOException e) {
            throw new com.benxin.llm.core.model.ModelException("SSE 解析失败", e);
        }
    }
}