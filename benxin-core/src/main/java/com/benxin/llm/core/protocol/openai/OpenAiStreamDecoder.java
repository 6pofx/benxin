package com.benxin.llm.core.protocol.openai;

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
import com.benxin.llm.core.protocol.SseEvent;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * OpenAI Chat Completions 的流式解码器：把一条条 SSE 分片翻译成
 * {@link LlmStreamHandler} 回调，并把增量片段拼装成完整结果。
 *
 * <p>流式协议最大的坑在工具调用：{@code id} / {@code name} 只出现在第一个分片里，
 * {@code arguments} 是被切成多段的 JSON 文本片段。本类按 {@code index} 归并这些分片，
 * 只有拼装完整后才回调一次 {@link LlmStreamHandler#onToolCall}，把复杂度挡在上层之外。</p>
 *
 * <p>单条分片解码失败只记 debug 日志并跳过，绝不中断整条流——生产环境里
 * 兼容网关偶发的心跳帧、非标准字段造成的噪音不应该让用户丢掉已经收到的内容。</p>
 *
 * <p>本类按"一条流一个实例"使用，不做跨线程同步（SSE 消费本身是单线程的）。</p>
 */
public final class OpenAiStreamDecoder implements StreamDecoder {

    private static final Logger log = LoggerFactory.getLogger(OpenAiStreamDecoder.class);

    private final ModelConfig config;
    private final LlmStreamHandler handler;

    /** 累计正文。 */
    private final StringBuilder text = new StringBuilder();

    /** 累计思维链（DeepSeek-R1 / Qwen-Thinking 等）。 */
    private final StringBuilder thinking = new StringBuilder();

    /** 按 index 归并的工具调用分片；TreeMap 保证回调顺序与 index 一致。 */
    private final Map<Integer, ToolCallBuffer> toolCalls = new TreeMap<>();

    /** 已经回调过的工具调用 index，保证每个工具调用恰好回调一次。 */
    private final Set<Integer> emittedToolCalls = new HashSet<>();

    /** 已回调的工具调用（顺序与 index 一致），用于组装最终的 ChatResponse。 */
    private final List<ToolUsePart> completedToolCalls = new ArrayList<>();

    private Usage usage = Usage.ZERO;
    private String finishReasonWire;
    private String responseId;
    private String model;
    private String systemFingerprint;

    /** finish() 幂等标记。 */
    private boolean finished;

    /** 流已异常中断：此时不再补发 onComplete，避免"错误之后又报成功"。 */
    private boolean failed;

    public OpenAiStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        this.config = config;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * 消费一条 SSE 事件。
     *
     * <p>注意：{@code onStart()} 由传输层（{@code HttpLlmModel.stream}）在发起请求前调用，
     * 本解码器不重复触发，以免监听者收到两次开始事件。</p>
     */
    @Override
    public void accept(SseEvent event) {
        if (event == null || event.isBlank() || finished || failed) {
            return;
        }
        if (event.isDone()) {
            // [DONE] 是 OpenAI 的流终止标记：立即收尾，不依赖调用方是否再调 finish()
            finish();
            return;
        }
        ChunkView view;
        try {
            JsonNode chunk = Json.parseQuietly(event.data());
            if (chunk == null || !chunk.isObject()) {
                log.debug("OpenAI 流式分片不是 JSON 对象，已跳过: {}", Json.abbreviate(event.data()));
                return;
            }
            view = parseChunk(chunk);
        } catch (Exception e) {
            // 只兜解析阶段的异常；用户回调抛出的异常照常上抛，交由传输层走 error() 通道
            log.debug("OpenAI 流式分片解码失败，已跳过: {}", Json.abbreviate(event.data()), e);
            return;
        }
        dispatch(view);
    }

    /**
     * 收尾：补齐仍可能未回调的工具调用，并发出唯一的 {@code onComplete}。
     *
     * <p>幂等：SSE 里 {@code [DONE]} 与传输层的正常结束都会触发它，重复调用没有副作用。</p>
     */
    @Override
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (failed) {
            log.debug("OpenAI 流已中断，跳过 onComplete");
            return;
        }
        emitToolCalls();

        List<ContentPart> parts = new ArrayList<>();
        if (!thinking.isEmpty()) {
            parts.add(new ThinkingPart(thinking.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(completedToolCalls);
        ChatMessage message = ChatMessage.assistant(parts);

        FinishReason reason = FinishReason.fromWire(finishReasonWire);
        if (reason == FinishReason.UNKNOWN) {
            // 上游没给 finish_reason 时按实际收到的内容兜底推断，语义与 StreamCollector 保持一致
            if (!completedToolCalls.isEmpty()) {
                reason = FinishReason.TOOL_CALLS;
            } else if (text.length() > 0 || thinking.length() > 0) {
                reason = FinishReason.STOP;
            }
        }

        String effectiveModel = model != null ? model : (config == null ? null : config.resolveModel(null));
        ChatResponse.Builder builder = ChatResponse.builder()
                .id(responseId)
                .model(effectiveModel)
                .message(message)
                .finishReason(reason)
                .usage(usage);
        if (responseId != null) {
            builder.raw("id", responseId);
        }
        if (systemFingerprint != null) {
            builder.raw("system_fingerprint", systemFingerprint);
        }
        if (finishReasonWire != null) {
            builder.raw("finish_reason", finishReasonWire);
        }
        handler.onComplete(builder.build());
    }

    /** 流出错：转发给监听者，并标记本次流不再补发 onComplete。 */
    @Override
    public void error(Throwable cause) {
        if (cause == null) {
            return;
        }
        boolean first = !failed;
        failed = true;
        log.debug("OpenAI 流式解码中断: {}", cause.toString());
        if (first) {
            handler.onError(cause);
        }
    }

    // ------------------------------------------------------------------
    // 分片解析
    // ------------------------------------------------------------------

    /**
     * 解析单条分片为中间结构，全程只读不改状态。
     *
     * <p>拆成"解析 → 累积 → 回调"三步，是为了让解析期的异常可以被安全吞掉，
     * 而用户回调里的异常不被误吞。</p>
     */
    private ChunkView parseChunk(JsonNode chunk) {
        Usage chunkUsage = chunk.has("usage") ? OpenAiCodec.parseUsage(chunk.get("usage")) : null;
        String textDelta = null;
        String thinkingDelta = null;
        List<ToolFragment> fragments = List.of();
        String reason = null;

        JsonNode choices = chunk.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            if (choice != null && choice.isObject()) {
                JsonNode delta = choice.get("delta");
                if (delta != null && delta.isObject()) {
                    String content = OpenAiCodec.readContent(delta.get("content"));
                    textDelta = content.isEmpty() ? null : content;
                    thinkingDelta = firstNonBlank(OpenAiCodec.textOrNull(delta.get("reasoning_content")),
                            OpenAiCodec.textOrNull(delta.get("reasoning")));
                    fragments = parseToolFragments(delta.get("tool_calls"));
                }
                reason = OpenAiCodec.textOrNull(choice.get("finish_reason"));
            }
        }
        return new ChunkView(OpenAiCodec.textOrNull(chunk.get("id")),
                OpenAiCodec.textOrNull(chunk.get("model")),
                OpenAiCodec.textOrNull(chunk.get("system_fingerprint")),
                textDelta, thinkingDelta, fragments, reason, chunkUsage);
    }

    /**
     * 解析 {@code delta.tool_calls} 分片。
     *
     * <p>{@code index} 是同一轮里工具调用的稳定标识；个别网关不返回该字段时
     * 退化为数组下标，仍能把同一条调用的分片归并到一起。</p>
     */
    private List<ToolFragment> parseToolFragments(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolFragment> fragments = new ArrayList<>(toolCalls.size());
        int position = 0;
        for (JsonNode call : toolCalls) {
            if (call == null || !call.isObject()) {
                position++;
                continue;
            }
            int index = call.path("index").asInt(position);
            JsonNode function = call.get("function");
            fragments.add(new ToolFragment(index,
                    OpenAiCodec.textOrNull(call.get("id")),
                    function == null ? null : OpenAiCodec.textOrNull(function.get("name")),
                    function == null ? null : OpenAiCodec.textOrNull(function.get("arguments"))));
            position++;
        }
        return fragments;
    }

    /** 累积状态并回调监听者。 */
    private void dispatch(ChunkView view) {
        if (view.id() != null) {
            responseId = view.id();
        }
        if (view.model() != null) {
            model = view.model();
        }
        if (view.systemFingerprint() != null) {
            systemFingerprint = view.systemFingerprint();
        }
        if (view.usage() != null) {
            // OpenAI 的 usage 描述"整段响应"的累计值而非增量，故覆盖而不累加，避免重复计费式膨胀
            usage = view.usage();
            if (!usage.isEmpty()) {
                handler.onUsage(usage);
            }
        }
        if (view.thinkingDelta() != null) {
            thinking.append(view.thinkingDelta());
            handler.onThinkingDelta(view.thinkingDelta());
        }
        if (view.textDelta() != null) {
            text.append(view.textDelta());
            handler.onTextDelta(view.textDelta());
        }
        for (ToolFragment fragment : view.toolFragments()) {
            bufferFor(fragment.index()).append(fragment);
        }
        if (view.finishReason() != null && !view.finishReason().isBlank()) {
            finishReasonWire = view.finishReason();
            // finish_reason 是该 choice 的终止信号，此刻参数分片已到齐，可提前把工具调用交给上层执行
            emitToolCalls();
        }
    }

    private ToolCallBuffer bufferFor(int index) {
        ToolCallBuffer buffer = toolCalls.get(index);
        if (buffer == null) {
            buffer = new ToolCallBuffer(index);
            toolCalls.put(index, buffer);
        }
        return buffer;
    }

    /** 把尚未回调过的完整工具调用逐个回调一次（finish_reason 到达时先发一轮，finish() 再兜底）。 */
    private void emitToolCalls() {
        for (ToolCallBuffer buffer : toolCalls.values()) {
            if (!emittedToolCalls.add(buffer.index)) {
                continue;
            }
            ToolUsePart toolUse = buffer.toToolUse();
            completedToolCalls.add(toolUse);
            handler.onToolCall(toolUse);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    /** 单条分片解析后的中间结果；解析期异常在构造它之前就被收敛掉了。 */
    private record ChunkView(String id,
                             String model,
                             String systemFingerprint,
                             String textDelta,
                             String thinkingDelta,
                             List<ToolFragment> toolFragments,
                             String finishReason,
                             Usage usage) {
    }

    /** 一次工具调用的增量片段。 */
    private record ToolFragment(int index, String id, String name, String arguments) {
    }

    /** 按 index 累积工具调用：id/name 只取首个非空值，arguments 逐段拼接。 */
    private static final class ToolCallBuffer {

        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallBuffer(int index) {
            this.index = index;
        }

        private void append(ToolFragment fragment) {
            // id / name 只在第一个分片出现；重复携带时保留首个，避免拼接出 "get_weatherget_weather"
            if (id == null && fragment.id() != null && !fragment.id().isBlank()) {
                id = fragment.id();
            }
            if (name == null && fragment.name() != null && !fragment.name().isBlank()) {
                name = fragment.name();
            }
            if (fragment.arguments() != null) {
                arguments.append(fragment.arguments());
            }
        }

        private ToolUsePart toToolUse() {
            // 网关漏发 id 时合成 call_<index>：没有 id 的工具结果无法回灌给模型
            String callId = id == null || id.isBlank() ? "call_" + index : id;
            String json = arguments.length() == 0 ? "{}" : arguments.toString();
            return new ToolUsePart(callId, name == null ? "" : name, json);
        }
    }
}
