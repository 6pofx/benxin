package com.benxin.llm.core.transport;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.SseParser;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 由「协议编解码器 + 传输层 + 配置」组装出的通用模型实现。
 *
 * <p>三个内置模型（OpenAI / Anthropic / Gemini）其实都只是它的不同装配方式，
 * 这也是"协议"与"厂商"解耦的直接体现：换 baseUrl 就是换厂商，换 Codec 就是换协议。</p>
 */
public class HttpLlmModel implements LlmModel {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmModel.class);

    private final ModelConfig config;
    private final ProtocolCodec codec;
    private final HttpTransport transport;
    private final ModelCapabilities capabilities;

    public HttpLlmModel(ModelConfig config, ProtocolCodec codec, HttpTransport transport) {
        this(config, codec, transport, ModelCapabilities.DEFAULT);
    }

    public HttpLlmModel(ModelConfig config, ProtocolCodec codec, HttpTransport transport,
                        ModelCapabilities capabilities) {
        this.config = config;
        this.codec = codec;
        this.transport = transport;
        this.capabilities = capabilities == null ? ModelCapabilities.DEFAULT : capabilities;
    }

    @Override
    public String name() {
        return config.name();
    }

    public ModelConfig config() {
        return config;
    }

    public ProtocolCodec codec() {
        return codec;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatRequest merged = merge(request, false);
        String url = codec.endpoint(config, merged);
        String body = codec.encode(merged, config);
        if (log.isDebugEnabled()) {
            log.debug("[{}] POST {} <- {}", name(), url, Json.abbreviate(body));
        }
        TransportResponse response = transport.post(url, codec.headers(config), body, config.readTimeout());
        if (!response.isSuccess()) {
            throw new ModelException("模型 [" + name() + "] 返回 HTTP " + response.status() + ": "
                    + Json.abbreviate(response.body()), response.status(), null);
        }
        return codec.decode(response.body(), config);
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        ChatRequest merged = merge(request, true);
        String url = codec.endpoint(config, merged);
        String body = codec.encode(merged, config);
        if (log.isDebugEnabled()) {
            log.debug("[{}] POST(stream) {} <- {}", name(), url, Json.abbreviate(body));
        }
        Map<String, String> headers = new LinkedHashMap<>(codec.headers(config));
        headers.put("Accept", "text/event-stream");

        // onStart 由各协议解码器在自己的"流真正开始"时机触发（例如 Anthropic 的 message_start），
        // 这里只保证下游最多收到一次；否则解码器再发一次会让订阅方重复初始化。
        LlmStreamHandler downstream = new StartGuard(handler);
        TransportResponse response = transport.postStreaming(url, headers, body, config.streamReadTimeout());
        if (!response.isSuccess()) {
            downstream.onError(new ModelException("模型 [" + name() + "] 返回 HTTP " + response.status() + ": "
                    + Json.abbreviate(response.body()), response.status(), null));
            return;
        }
        StreamDecoder decoder = codec.newStreamDecoder(config, downstream);
        try (InputStream in = response.stream()) {
            SseParser.parse(in, decoder::accept);
            decoder.finish();
        } catch (Exception e) {
            decoder.error(e);
            downstream.onError(e);
        }
    }

    /**
     * 保证下游恰好收到一次 {@code onStart}。
     *
     * <p>三种协议对"流何时开始"的定义不同（OpenAI 无显式起始事件、Anthropic 有
     * {@code message_start}、Gemini 首帧即数据），与其在三处各写一遍约定，
     * 不如由传输层统一收口：谁先触发都行，下游只被通知一次。</p>
     */
    private static final class StartGuard implements LlmStreamHandler {

        private final LlmStreamHandler downstream;
        private boolean started;
        private boolean errored;

        private StartGuard(LlmStreamHandler downstream) {
            this.downstream = downstream;
        }

        private void ensureStarted() {
            if (!started) {
                started = true;
                downstream.onStart();
            }
        }

        @Override
        public void onStart() {
            ensureStarted();
        }

        @Override
        public void onTextDelta(String delta) {
            ensureStarted();
            downstream.onTextDelta(delta);
        }

        @Override
        public void onThinkingDelta(String delta) {
            ensureStarted();
            downstream.onThinkingDelta(delta);
        }

        @Override
        public void onToolCall(com.benxin.llm.core.message.ToolUsePart toolUse) {
            ensureStarted();
            downstream.onToolCall(toolUse);
        }

        @Override
        public void onUsage(com.benxin.llm.core.chat.Usage usage) {
            ensureStarted();
            downstream.onUsage(usage);
        }

        @Override
        public void onComplete(com.benxin.llm.core.chat.ChatResponse response) {
            ensureStarted();
            downstream.onComplete(response);
        }

        @Override
        public void onError(Throwable error) {
            ensureStarted();
            // 解码器与传输层可能都会上报同一个异常（例如解码器内部已转发一次），
            // 这里去重，保证订阅方只被通知一次 —— 重试逻辑最怕的就是"错误来了两遍"。
            if (errored) {
                return;
            }
            errored = true;
            downstream.onError(error);
        }
    }

    /** 把配置里的默认参数合并进请求；请求显式设置的优先。 */
    private ChatRequest merge(ChatRequest request, boolean stream) {
        ChatRequest.Builder b = request.toBuilder().stream(stream);
        if (request.model() == null || request.model().isBlank()) {
            b.model(config.resolveModel(request));
        }
        if (request.temperature() == null && config.temperature() != null) {
            b.temperature(config.temperature());
        }
        if (request.maxTokens() == null && config.maxTokens() != null) {
            b.maxTokens(config.maxTokens());
        }
        // 配置级 extra 作为底，请求级 extra 覆盖之
        Map<String, Object> mergedExtra = new LinkedHashMap<>(config.extra());
        mergedExtra.putAll(request.extra());
        b.extra(mergedExtra);
        return b.build();
    }

    @Override
    public String toString() {
        return "HttpLlmModel(" + config + ")";
    }
}