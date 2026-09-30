package com.benxin.llm.core.protocol.responses;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.SseEvent;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Responses API 的 SSE 流式解码器。
 *
 * <p>与 OpenAI Chat Completions 的流式相比有三处结构性差异，写在这里以免后来者踩：</p>
 *
 * <ol>
 *   <li><b>事件是命名的</b>：{@code event: response.output_text.delta}，
 *       而不是清一色的匿名 {@code data:}。不过本实现优先读 payload 里的 {@code type} 字段、
 *       只有它缺失时才回落到 SSE 的 {@code event:} 名 —— 部分网关会吃掉 {@code event:} 行。</li>
 *   <li><b>没有 {@code data: [DONE]}</b>：流的结束靠 {@code response.completed} /
 *       {@code response.incomplete} / {@code response.failed} 三个终态事件，
 *       因此 {@link #finish()} 必须在传输层读到流尾时兜底收尾。</li>
 *   <li><b>工具调用是独立 item</b>：参数靠 {@code response.function_call_arguments.delta}
 *       按 {@code output_index} 分片累积，完整信息在 {@code response.output_item.done} 里。</li>
 * </ol>
 *
 * <p>本类非线程安全，一次流用一个实例。</p>
 */
public final class ResponsesStreamDecoder implements StreamDecoder {

    private static final Logger log = LoggerFactory.getLogger(ResponsesStreamDecoder.class);

    private final ModelConfig config;
    private final LlmStreamHandler handler;

    /** 按 output_index 累积中的函数调用；用 TreeMap 保证最终回调顺序稳定。 */
    private final TreeMap<Integer, CallState> calls = new TreeMap<>();

    /** 已经回调过 onToolCall 的 call_id，避免 done 与 finish 兜底重复投递。 */
    private final Set<String> emittedCalls = new HashSet<>();

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();

    private boolean started;
    private boolean finished;
    private boolean failed;
    private boolean sawTextDelta;
    private boolean sawThinkingDelta;

    private String responseId;
    private String model;
    private String status;
    private Usage usage = Usage.ZERO;

    public ResponsesStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        this.config = config;
        this.handler = handler == null ? new LlmStreamHandler() { } : handler;
    }

    /** 一次函数调用的累积状态。 */
    private static final class CallState {
        private final String callId;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        private CallState(String callId, String name) {
            this.callId = callId;
            this.name = name;
        }

        private ToolUsePart toPart() {
            String json = arguments.toString();
            return new ToolUsePart(callId, name, json.isBlank() ? "{}" : json);
        }
    }

    @Override
    public void accept(SseEvent event) {
        if (event == null) {
            return;
        }
        if (event.isDone()) {
            // Responses 不发 [DONE]，但兼容网关可能补一个；照 OpenAI 的约定处理
            finish();
            return;
        }
        if (event.isBlank()) {
            return;
        }
        JsonNode payload = Json.parseQuietly(event.data());
        if (payload == null || !payload.isObject()) {
            log.debug("跳过无法解析的 Responses SSE 帧: {}", Json.abbreviate(event.data()));
            return;
        }
        // payload 自带 type 时以它为准；网关吃掉 event: 行时 eventName 会是 null
        String type = firstNonBlank(textOrNull(payload.get("type")), event.event());
        if (type == null) {
            log.debug("Responses SSE 帧既无 type 也无 event 名，跳过: {}", Json.abbreviate(event.data()));
            return;
        }

        switch (type) {
            case "response.created", "response.in_progress" -> {
                ensureStarted();
                captureMeta(payload);
            }
            case "response.output_item.added" -> onOutputItem(payload, false);
            case "response.output_item.done" -> onOutputItem(payload, true);
            case "response.output_text.delta" -> {
                ensureStarted();
                String delta = textOrNull(payload.get("delta"));
                if (delta != null) {
                    sawTextDelta = true;
                    text.append(delta);
                    handler.onTextDelta(delta);
                }
            }
            case "response.output_text.done" -> {
                // 少数网关只发 done 不发 delta：此时把整段文本当作一次增量补上，
                // 否则订阅方会拿到空回答（静默失败比报错更难查）
                if (!sawTextDelta) {
                    String full = textOrNull(payload.get("text"));
                    if (full != null && !full.isEmpty()) {
                        sawTextDelta = true;
                        text.append(full);
                        handler.onTextDelta(full);
                    }
                }
            }
            case "response.reasoning_text.delta" -> {
                ensureStarted();
                String delta = textOrNull(payload.get("delta"));
                if (delta != null) {
                    sawThinkingDelta = true;
                    thinking.append(delta);
                    handler.onThinkingDelta(delta);
                }
            }
            case "response.reasoning_text.done" -> {
                if (!sawThinkingDelta) {
                    String full = textOrNull(payload.get("text"));
                    if (full != null && !full.isEmpty()) {
                        sawThinkingDelta = true;
                        thinking.append(full);
                        handler.onThinkingDelta(full);
                    }
                }
            }
            case "response.function_call_arguments.delta" -> {
                CallState state = callAt(payload);
                if (state != null) {
                    String delta = textOrNull(payload.get("delta"));
                    if (delta != null) {
                        state.arguments.append(delta);
                    }
                }
            }
            case "response.function_call_arguments.done" -> {
                CallState state = callAt(payload);
                if (state != null) {
                    // done 里的 arguments 是权威的完整值，覆盖掉分片累积的结果
                    String full = textOrNull(payload.get("arguments"));
                    if (full != null) {
                        state.arguments.setLength(0);
                        state.arguments.append(full);
                    }
                }
            }
            case "response.completed", "response.incomplete", "response.failed" -> {
                captureMeta(payload);
                JsonNode response = payload.get("response");
                if (response != null && response.isObject()) {
                    captureMeta(response);
                    usage = parseUsage(response.get("usage"));
                }
                if ("response.failed".equals(type)) {
                    failed = true;
                    // 失败事件里带 error 详情；没有就退化为状态描述
                    String message = response == null ? null : errorText(response.get("error"));
                    handler.onError(new ModelException("Responses 流以失败结束: "
                            + (message == null ? "未提供错误详情" : message)
                            + " | status=" + status));
                } else {
                    status = "response.incomplete".equals(type) ? "incomplete" : "completed";
                }
                finish();
            }
            case "error" -> {
                failed = true;
                handler.onError(new ModelException("Responses 流返回错误事件: " + errorText(payload.get("error"))
                        + " | body=" + Json.abbreviate(event.data())));
            }
            default -> log.debug("跳过 Responses SSE 事件 [{}]", type);
        }
    }

    /** 处理 {@code output_item.added} / {@code output_item.done}。 */
    private void onOutputItem(JsonNode payload, boolean done) {
        ensureStarted();
        JsonNode item = payload.get("item");
        if (item == null || !item.isObject()) {
            return;
        }
        String itemType = textOrNull(item.get("type"));
        if (!"function_call".equals(itemType)) {
            // message / reasoning 的内容已经由各自的 delta 事件送达，这里无需重复处理
            return;
        }
        CallState state = callAt(payload);
        if (state == null) {
            return;
        }
        if (done) {
            // done 里的 arguments 是完整值，优先采用
            String full = textOrNull(item.get("arguments"));
            if (full != null) {
                state.arguments.setLength(0);
                state.arguments.append(full);
            }
            emitCall(state);
        }
    }

    /** 取（或懒建）某个 output_index 上的函数调用状态。 */
    private CallState callAt(JsonNode payload) {
        int index = payload.path("output_index").asInt(0);
        CallState existing = calls.get(index);
        if (existing != null) {
            return existing;
        }
        JsonNode item = payload.get("item");
        String callId = item == null ? null : textOrNull(item.get("call_id"));
        String name = item == null ? null : textOrNull(item.get("name"));
        if (callId == null && name == null) {
            // delta 先于 added 到达时（部分网关如此）建不出可用状态，
            // 但 pair 的 arguments 仍值得留存，用占位 id 兜底
            log.debug("Responses 流在 output_index={} 上先收到参数分片，使用占位 call_id", index);
        }
        CallState created = new CallState(orDefault(callId, "call_" + index), orDefault(name, ""));
        calls.put(index, created);
        return created;
    }

    private void emitCall(CallState state) {
        if (!emittedCalls.add(state.callId)) {
            return;
        }
        handler.onToolCall(state.toPart());
    }

    private void captureMeta(JsonNode node) {
        if (node == null) {
            return;
        }
        // response.created 的元信息在 payload.response 下，completed 同层
        JsonNode source = node.has("response") && node.get("response").isObject()
                ? node.get("response") : node;
        String id = textOrNull(source.get("id"));
        if (id != null) {
            responseId = id;
        }
        String modelName = textOrNull(source.get("model"));
        if (modelName != null) {
            model = modelName;
        }
        String state = textOrNull(source.get("status"));
        if (state != null) {
            status = state;
        }
        JsonNode usageNode = source.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            usage = parseUsage(usageNode);
        }
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        ensureStarted();
        if (failed) {
            // 出错后不再补 onComplete：否则 StreamCollector 会把半截响应当成功继续跑
            return;
        }
        // 没等到 output_item.done 就断流的（网络中断、网关提前关闭）也要把已累积的工具调用交出去，
        // 否则模型请求过的工具会被静默丢弃
        calls.values().forEach(this::emitCall);
        if (!usage.isEmpty()) {
            handler.onUsage(usage);
        }
        handler.onComplete(ChatResponse.builder()
                .id(responseId)
                .model(model)
                .message(ChatMessage.assistant(assembleParts()))
                .finishReason(finishReasonOf())
                .usage(usage)
                .raw("status", status)
                .build());
    }

    private List<ContentPart> assembleParts() {
        List<ContentPart> parts = new ArrayList<>();
        if (!thinking.isEmpty()) {
            parts.add(new ThinkingPart(thinking.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        calls.values().forEach(state -> parts.add(state.toPart()));
        return parts;
    }

    private FinishReason finishReasonOf() {
        if ("incomplete".equals(status)) {
            return FinishReason.LENGTH;
        }
        if (!calls.isEmpty()) {
            return FinishReason.TOOL_CALLS;
        }
        return FinishReason.STOP;
    }

    @Override
    public void error(Throwable cause) {
        failed = true;
        handler.onError(cause);
    }

    private void ensureStarted() {
        if (!started) {
            started = true;
            handler.onStart();
        }
    }

    private Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return Usage.ZERO;
        }
        return new Usage(
                usageNode.path("input_tokens").asInt(0),
                usageNode.path("output_tokens").asInt(0),
                usageNode.path("input_tokens_details").path("cached_tokens").asInt(0),
                usageNode.path("output_tokens_details").path("reasoning_tokens").asInt(0));
    }

    private String errorText(JsonNode error) {
        if (error == null || error.isNull()) {
            return null;
        }
        String message = textOrNull(error.get("message"));
        String code = textOrNull(error.get("code"));
        StringBuilder sb = new StringBuilder(message == null ? error.toString() : message);
        if (code != null) {
            sb.append(" [code=").append(code).append(']');
        }
        return sb.toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isEmpty() ? null : value;
    }

    /** 供测试与日志观察当前配置。 */
    ModelConfig config() {
        return config;
    }
}
