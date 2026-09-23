package com.benxin.llm.core.protocol.gemini;

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
import java.util.List;

/**
 * Gemini 流式解码器，对应 {@code POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse}。
 *
 * <p>与 OpenAI 的流式差异：</p>
 * <ul>
 *   <li>每个分片的结构与非流式响应完全一致（{@code candidates[0].content.parts} 增量），
 *       而不是 OpenAI 那种 {@code choices[0].delta} 结构；</li>
 *   <li>{@code functionCall} 一次性给全，不存在 {@code arguments} 分片拼接；</li>
 *   <li>没有 {@code data: [DONE]} 结束标记，靠连接关闭表示结束，因此
 *       {@link #finish()} 才是发出 {@code onComplete} 的地方；</li>
 *   <li>{@code usageMetadata} 只在末尾分片（或每个分片累计）出现。</li>
 * </ul>
 *
 * <p>工具调用 id 与 {@link GeminiCodec} 使用同一套合成规则（{@code call_<n>}），
 * 序号跨分片递增，保证流式与非流式两条路径下 id 语义一致、{@code tool_result} 能对上。</p>
 */
public final class GeminiStreamDecoder implements StreamDecoder {

    private static final Logger log = LoggerFactory.getLogger(GeminiStreamDecoder.class);

    private final ModelConfig config;
    private final LlmStreamHandler handler;

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<ToolUsePart> toolUses = new ArrayList<>();

    /** 工具调用序号：跨分片递增，不复位。 */
    private int toolCallSequence;
    private boolean started;
    private boolean finished;
    private boolean failed;

    private FinishReason finishReason = FinishReason.UNKNOWN;
    private Usage usage = Usage.ZERO;
    private String responseId;
    private String modelVersion;

    public GeminiStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        this.config = config;
        this.handler = handler == null ? new LlmStreamHandler() {
        } : handler;
    }

    @Override
    public void accept(SseEvent event) {
        if (finished || event == null || event.isBlank()) {
            return;
        }
        // Gemini 的 alt=sse 流没有 [DONE]；这里顺手兼容一下会补发该标记的兼容网关
        if (event.isDone()) {
            return;
        }
        JsonNode root;
        try {
            root = Json.parse(event.data());
        } catch (RuntimeException e) {
            // 单个分片坏掉不应该打断整条流
            log.debug("跳过无法解析的 Gemini 流式分片: {}", Json.abbreviate(event.data()));
            return;
        }
        if (root == null || !root.isObject()) {
            log.debug("跳过结构非法的 Gemini 流式分片: {}", Json.abbreviate(event.data()));
            return;
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            // 部分网关会在 HTTP 200 的流里回错误体：转发给 handler 并标记失败，
            // 避免收尾时再补一个 onComplete 把错误吞掉。
            failed = true;
            String message = error.isObject() ? error.path("message").asText(Json.write(error)) : error.asText();
            log.warn("Gemini 流式返回错误分片: {}", Json.abbreviate(message));
            handler.onError(new ModelException("Gemini 流式返回错误: " + message));
            return;
        }

        // 收到任何分片都保证 onStart 先于其它回调发出
        start();
        readMeta(root);
        readCandidate(root);
        readUsage(root);
    }

    @Override
    public void finish() {
        // 幂等：HttpLlmModel 在异常路径上可能重复调用
        if (finished) {
            return;
        }
        finished = true;
        if (failed) {
            // 已经通过 onError 上报过，不再补发 onComplete
            return;
        }
        FinishReason reason = finishReason;
        if (reason == FinishReason.UNKNOWN) {
            // Gemini 正常收尾时一定会给 finishReason；真缺失（例如被截断）时按内容兜底，
            // 与 StreamCollector 的兜底口径保持一致。
            reason = toolUses.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        }
        List<ContentPart> parts = new ArrayList<>();
        if (!thinking.isEmpty()) {
            parts.add(new ThinkingPart(thinking.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(toolUses);

        ChatResponse.Builder builder = ChatResponse.builder()
                .message(ChatMessage.assistant(parts))
                .finishReason(reason)
                .usage(usage);
        if (responseId != null) {
            builder.id(responseId);
        }
        if (modelVersion != null) {
            builder.model(modelVersion);
        } else if (config != null) {
            builder.model(config.model());
        }
        handler.onComplete(builder.build());
    }

    @Override
    public void error(Throwable cause) {
        if (cause == null) {
            return;
        }
        failed = true;
        // 注意：HttpLlmModel 的异常分支也会调用 handler.onError，因此上层需容忍
        // 这里的转发与它自身的上报可能重复（接口约定如此）。
        handler.onError(cause);
    }

    // ------------------------------------------------------------------ 内部

    private void start() {
        if (started) {
            return;
        }
        started = true;
        // HttpLlmModel 在发起请求前已经回调过一次 onStart；这里做兜底，
        // 保证只持有解码器的使用方（例如单元测试、自定义传输层）也能拿到开始信号。
        handler.onStart();
    }

    private void readMeta(JsonNode root) {
        String id = GeminiCodec.stringOf(root, "responseId");
        if (id != null) {
            responseId = id;
        }
        String version = GeminiCodec.stringOf(root, "modelVersion");
        if (version != null) {
            modelVersion = version;
        }
    }

    private void readCandidate(JsonNode root) {
        JsonNode candidate = GeminiCodec.firstCandidate(root);
        if (candidate == null) {
            return;
        }
        String rawFinish = GeminiCodec.stringOf(candidate, "finishReason");
        if (rawFinish != null && !rawFinish.isBlank()) {
            // 最后一个带 finishReason 的分片即最终结束原因
            finishReason = FinishReason.fromWire(rawFinish);
        }
        JsonNode parts = GeminiCodec.field(candidate.path("content"), "parts");
        if (parts == null || !parts.isArray()) {
            return;
        }
        for (JsonNode part : parts) {
            if (part == null || !part.isObject()) {
                continue;
            }
            String delta = GeminiCodec.stringOf(part, "text");
            if (delta != null) {
                if (part.path("thought").asBoolean(false)) {
                    thinking.append(delta);
                    handler.onThinkingDelta(delta);
                } else {
                    text.append(delta);
                    handler.onTextDelta(delta);
                }
                continue;
            }
            JsonNode functionCall = part.get("functionCall");
            if (functionCall != null && functionCall.isObject()) {
                toolCallSequence++;
                JsonNode args = functionCall.get("args");
                String argumentsJson = args == null || args.isNull() ? "{}" : Json.write(args);
                // Gemini 流式下 functionCall 一次性给全（不像 OpenAI 需要按 index 拼 arguments 分片），
                // 收到即回调；id 由本心按出现顺序合成，序号跨分片递增，保证与 tool_result 的对应稳定。
                ToolUsePart toolUse = new ToolUsePart(GeminiCodec.synthesizeToolCallId(toolCallSequence),
                        functionCall.path("name").asText(""), argumentsJson);
                toolUses.add(toolUse);
                handler.onToolCall(toolUse);
            }
        }
    }

    private void readUsage(JsonNode root) {
        JsonNode usageMetadata = root.get("usageMetadata");
        if (usageMetadata == null || !usageMetadata.isObject()) {
            return;
        }
        // Gemini 的 usageMetadata 是累计值：直接以最后出现的分片为准
        usage = GeminiCodec.decodeUsage(usageMetadata);
        handler.onUsage(usage);
    }
}
