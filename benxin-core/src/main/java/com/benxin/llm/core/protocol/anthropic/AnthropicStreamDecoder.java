package com.benxin.llm.core.protocol.anthropic;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Messages API 的流式解码器。
 *
 * <p>Anthropic 是<b>强类型事件流</b>（每条 SSE 都带 {@code event:} 名），与 OpenAI 那种
 * "一个 chunk 走天下"完全不同。事件序列固定为：</p>
 *
 * <pre>
 * message_start → content_block_start → content_block_delta* → content_block_stop
 *               → …（多个 content block 交替出现）…
 *               → message_delta → message_stop
 * </pre>
 *
 * <p>两个关键点：</p>
 * <ul>
 *   <li><b>工具参数是 fragment</b>：{@code content_block_start} 只给出 id / name，
 *       JSON 参数要按 block index 累积 {@code input_json_delta.partial_json}，
 *       直到 {@code content_block_stop} 才能拼成完整调用——所以本类必须按 index 分别缓存。</li>
 *   <li><b>用量分两段上报</b>：{@code message_start} 给 input_tokens，{@code message_delta}
 *       给 output_tokens 的<b>累计值</b>；上层 {@code StreamCollector} 对标量做的是累加，
 *       因此这里要把累计值换算成增量再回调，否则 token 统计会偏大。</li>
 * </ul>
 */
public class AnthropicStreamDecoder implements StreamDecoder {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamDecoder.class);

    private final ModelConfig config;
    private final LlmStreamHandler handler;

    /** block index → 累积状态；LinkedHashMap 保证输出顺序与上游 content 顺序一致。 */
    private final Map<Integer, BlockState> blocks = new LinkedHashMap<>();

    private String messageId;
    private String model;
    private String stopReason;
    private String stopSequence;
    private int inputTokens;
    private int outputTokens;
    private int cachedInputTokens;
    /** 已按累计口径上报过的 output_tokens，用于把 message_delta 的累计值换算成增量。 */
    private int reportedOutputTokens;
    private boolean startNotified;
    private boolean finished;
    private boolean failed;

    public AnthropicStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        this.config = config;
        this.handler = handler == null ? new LlmStreamHandler() {
        } : handler;
    }

    @Override
    public void accept(SseEvent event) {
        if (event == null || event.isBlank()) {
            return;
        }
        // Anthropic 正常不会发 [DONE]，但兼容网关可能会补一个，这里顺手收尾
        if (event.isDone()) {
            finish();
            return;
        }

        JsonNode data;
        try {
            data = Json.parse(event.data());
        } catch (RuntimeException e) {
            // 单条事件坏掉不该把整条流带崩：记 debug 日志后跳过，继续处理后续事件
            log.debug("跳过无法解析的 SSE 事件: {}", Json.abbreviate(event.data()), e);
            return;
        }
        if (data == null || !data.isObject()) {
            log.debug("跳过非对象 SSE 数据: {}", Json.abbreviate(event.data()));
            return;
        }

        // 优先用 payload 里的 type 派发：部分网关会把 event: 行吃掉只留 data:，
        // 此时 event.event() 为空，靠 data.type 仍然能正确路由。
        String type = data.path("type").asText("");
        if (type.isEmpty()) {
            type = event.event() == null ? "" : event.event().trim();
        }

        switch (type) {
            case "message_start" -> handleMessageStart(data);
            case "content_block_start" -> handleContentBlockStart(data);
            case "content_block_delta" -> handleContentBlockDelta(data);
            case "content_block_stop" -> handleContentBlockStop(data);
            case "message_delta" -> handleMessageDelta(data);
            case "message_stop" -> finish();
            case "ping" -> {
                // 心跳，无需处理
            }
            case "error" -> handleErrorEvent(data);
            default -> log.debug("忽略未知 SSE 事件类型: {}", type);
        }
    }

    // ------------------------------------------------------------- 各事件处理

    private void handleMessageStart(JsonNode data) {
        JsonNode message = data.path("message");
        messageId = text(message, "id");
        model = text(message, "model");
        if (!startNotified) {
            startNotified = true;
            handler.onStart();
        }

        JsonNode usage = message.path("usage");
        if (!usage.isObject()) {
            return;
        }
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        cachedInputTokens = usage.path("cache_read_input_tokens").asInt(0);
        inputTokens += input;
        outputTokens += output;
        reportedOutputTokens = Math.max(reportedOutputTokens, output);
        handler.onUsage(new Usage(input, output, cachedInputTokens, 0));
    }

    private void handleContentBlockStart(JsonNode data) {
        int index = data.path("index").asInt(0);
        JsonNode block = data.path("content_block");
        String type = block.path("type").asText("");
        BlockState state = state(index, type);

        switch (type) {
            case "text" -> {
                // 正常流里这里是空串，真正的文本全在 text_delta 里；
                // 非空说明上游把首段文本塞进了 start 事件，累积起来即可（不再补一次 onTextDelta，
                // 避免与 delta 回调重复计数）。
                state.text.append(block.path("text").asText(""));
            }
            case "thinking" -> state.text.append(block.path("thinking").asText(""));
            case "tool_use" -> {
                state.toolId = text(block, "id");
                state.toolName = text(block, "name");
                // 标准流里 input 恒为空对象 {}，参数靠 input_json_delta 拼；
                // 个别网关会直接给完整对象，那就先序列化进来备用。
                JsonNode input = block.path("input");
                if (input.isObject() && input.size() > 0) {
                    state.json.append(Json.write(input));
                    state.jsonFromStart = true;
                }
            }
            case "redacted_thinking" ->
                    log.debug("忽略 redacted_thinking 内容块（index={}）：Anthropic 未公开其明文", index);
            default -> log.debug("忽略未知内容块类型: {}（index={}）", type, index);
        }
    }

    private void handleContentBlockDelta(JsonNode data) {
        int index = data.path("index").asInt(0);
        JsonNode delta = data.path("delta");
        String type = delta.path("type").asText("");

        switch (type) {
            case "text_delta" -> {
                String text = delta.path("text").asText("");
                if (!text.isEmpty()) {
                    state(index, "text").text.append(text);
                    handler.onTextDelta(text);
                }
            }
            case "thinking_delta" -> {
                String text = delta.path("thinking").asText("");
                if (!text.isEmpty()) {
                    state(index, "thinking").text.append(text);
                    handler.onThinkingDelta(text);
                }
            }
            case "signature_delta" ->
                    // signature 会被切成多段增量下发，必须累积，最后随 ThinkingPart 一起带回去
                    state(index, "thinking").signature.append(delta.path("signature").asText(""));
            case "input_json_delta" -> {
                BlockState state = state(index, "tool_use");
                if (state.jsonFromStart) {
                    // start 事件里已给完整 input，此时再来 delta 属于网关重复推送，丢弃前一份
                    state.json.setLength(0);
                    state.jsonFromStart = false;
                }
                // 工具参数成型的关键：partial_json 是任意切分的片段，必须按 index 原样拼接
                state.json.append(delta.path("partial_json").asText(""));
            }
            default -> log.debug("忽略未知 delta 类型: {}", type);
        }
    }

    private void handleContentBlockStop(JsonNode data) {
        int index = data.path("index").asInt(0);
        BlockState state = blocks.get(index);
        if (state == null || !"tool_use".equals(state.type) || state.completed) {
            return;
        }
        // 到这里参数才拼完，工具调用只回调这一次（重复的 stop 事件不会重复触发工具执行）
        state.completed = true;
        ToolUsePart toolUse = new ToolUsePart(resolveToolId(state, index),
                state.toolName == null ? "" : state.toolName, state.argumentsJson());
        handler.onToolCall(toolUse);
    }

    private void handleMessageDelta(JsonNode data) {
        JsonNode delta = data.path("delta");
        if (delta.hasNonNull("stop_reason")) {
            stopReason = delta.get("stop_reason").asText();
        }
        if (delta.hasNonNull("stop_sequence")) {
            stopSequence = delta.get("stop_sequence").asText();
        }

        JsonNode usage = data.path("usage");
        if (!usage.isObject()) {
            return;
        }
        // message_delta 的 output_tokens 是"截至当前的累计值"，而 message_start 报的是首帧值；
        // 上层 onUsage 的语义是增量累加，所以这里必须换算成差值，否则会重复计数。
        int total = usage.path("output_tokens").asInt(0);
        int outputDelta = Math.max(0, total - reportedOutputTokens);
        reportedOutputTokens = Math.max(reportedOutputTokens, total);

        int inputDelta = 0;
        if (inputTokens == 0 && usage.has("input_tokens")) {
            // 少数网关只在最后一帧上报输入 token，这里补一次，避免漏统计
            inputDelta = usage.path("input_tokens").asInt(0);
            inputTokens += inputDelta;
        }
        outputTokens += outputDelta;

        if (outputDelta > 0 || inputDelta > 0) {
            handler.onUsage(new Usage(inputDelta, outputDelta, 0, 0));
        }
    }

    private void handleErrorEvent(JsonNode data) {
        JsonNode error = data.path("error");
        String type = error.path("type").asText("error");
        String message = error.path("message").asText(data.path("message").asText("未知错误"));
        // 标记失败：错误已经上报过，finish() 不能再补一个 onComplete。
        // 否则 StreamCollector.response() 会优先返回 completed，把半截响应当成成功结果，
        // 把真实错误吞掉。
        failed = true;
        handler.onError(new ModelException("Anthropic 流式错误 [" + type + "]: " + message));
    }

    // ------------------------------------------------------------- 收尾

    @Override
    public void finish() {
        // 幂等：正常遇到 message_stop 会调一次，传输层读完流还会再调一次，只能发一个 onComplete
        if (finished) {
            return;
        }
        finished = true;
        if (failed) {
            log.debug("流已上报错误，跳过 onComplete");
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Anthropic 流结束 [{}]：{} 个内容块，stop_reason={}", config == null ? "?" : config.name(),
                    blocks.size(), stopReason);
        }

        ChatResponse response = ChatResponse.builder()
                .id(messageId)
                .model(model)
                .message(ChatMessage.assistant(assembleParts()))
                .finishReason(FinishReason.fromWire(stopReason))
                .usage(new Usage(inputTokens, outputTokens, cachedInputTokens, 0))
                .raw(assembleRaw())
                .build();
        handler.onComplete(response);
    }

    @Override
    public void error(Throwable cause) {
        if (cause == null) {
            return;
        }
        // 中断的流不再发 onComplete：上层若拿到一个"半截的成功响应"会掩盖真实错误
        finished = true;
        failed = true;
        handler.onError(cause);
    }

    /** 按 block index 顺序把累积状态组装成最终内容：文本 / 思考 / 工具调用可共存。 */
    private List<ContentPart> assembleParts() {
        List<ContentPart> parts = new ArrayList<>();
        for (Map.Entry<Integer, BlockState> entry : blocks.entrySet()) {
            int index = entry.getKey();
            BlockState state = entry.getValue();
            if (state.type == null) {
                continue;
            }
            switch (state.type) {
                case "text" -> {
                    if (!state.text.isEmpty()) {
                        parts.add(new TextPart(state.text.toString()));
                    }
                }
                case "thinking" -> {
                    String thinking = state.text.toString();
                    String signature = state.signature.isEmpty() ? null : state.signature.toString();
                    if (!thinking.isEmpty() || signature != null) {
                        parts.add(new ThinkingPart(thinking, signature));
                    }
                }
                case "tool_use" -> {
                    if (state.toolId == null && state.toolName == null) {
                        continue;
                    }
                    parts.add(new ToolUsePart(resolveToolId(state, index),
                            state.toolName == null ? "" : state.toolName, state.argumentsJson()));
                }
                default -> {
                    // 未知块类型不进入统一消息
                }
            }
        }
        return parts;
    }

    private Map<String, Object> assembleRaw() {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (stopReason != null) {
            raw.put("stop_reason", stopReason);
        }
        if (stopSequence != null) {
            raw.put("stop_sequence", stopSequence);
        }
        raw.put("stream", true);
        return raw;
    }

    /**
     * 取出（或惰性创建）某个 index 的累积状态。
     *
     * <p>惰性创建是为了容错：万一 {@code content_block_start} 丢了，delta 仍然能被累积，
     * 不至于整段内容消失。</p>
     */
    private BlockState state(int index, String type) {
        BlockState state = blocks.computeIfAbsent(index, BlockState::new);
        if (state.type == null && type != null && !type.isEmpty()) {
            state.type = type;
        }
        return state;
    }

    /** tool_use 的 id 缺失时合成一个：后续 tool_result 要靠它关联，不能为空。 */
    private String resolveToolId(BlockState state, int index) {
        if (state.toolId != null && !state.toolId.isBlank()) {
            return state.toolId;
        }
        state.toolId = "toolu_" + UUID.randomUUID().toString().replace("-", "");
        log.warn("tool_use 缺少 id（index={}），已合成 {}，请检查上游流是否完整", index, state.toolId);
        return state.toolId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 单个内容块的累积状态。 */
    private static final class BlockState {

        private final int index;
        private String type;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private final StringBuilder json = new StringBuilder();
        private String toolId;
        private String toolName;
        /** input 是否已经在 content_block_start 里给全（此时后续 delta 属于重复推送）。 */
        private boolean jsonFromStart;
        /** tool_use 是否已经回调过 onToolCall，防止重复的 stop 事件触发二次工具执行。 */
        private boolean completed;

        private BlockState(int index) {
            this.index = index;
        }

        String argumentsJson() {
            String value = json.toString().trim();
            if (value.isEmpty()) {
                return "{}";
            }
            if (Json.parseQuietly(value) == null) {
                // 半截 JSON 说明流被截断；原样交给上层，让它能明确报错而不是被静默替换成 {}
                log.warn("工具参数 JSON 拼接结果无法解析（index={}）: {}", index, Json.abbreviate(value));
            }
            return value;
        }
    }
}
