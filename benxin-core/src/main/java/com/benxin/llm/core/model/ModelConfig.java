package com.benxin.llm.core.model;

import com.benxin.llm.core.protocol.Protocol;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一个模型端点的完整配置。对应 {@code llm.models.<name>.*}。
 */
public final class ModelConfig {

    private final String name;
    private final Protocol protocol;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Map<String, String> headers;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration streamReadTimeout;
    private final int maxRetries;
    private final Double temperature;
    private final Integer maxTokens;
    private final Map<String, Object> extra;

    private ModelConfig(Builder b) {
        this.name = b.name;
        this.protocol = b.protocol == null ? Protocol.OPENAI : b.protocol;
        this.baseUrl = trimTrailingSlash(b.baseUrl);
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.streamReadTimeout = b.streamReadTimeout;
        this.maxRetries = b.maxRetries;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(b.extra));
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 基于本配置派生一个新配置（用于临时切模型）。 */
    public Builder toBuilder() {
        Builder b = new Builder(name);
        b.protocol = protocol;
        b.baseUrl = baseUrl;
        b.apiKey = apiKey;
        b.model = model;
        b.headers.putAll(headers);
        b.connectTimeout = connectTimeout;
        b.readTimeout = readTimeout;
        b.streamReadTimeout = streamReadTimeout;
        b.maxRetries = maxRetries;
        b.temperature = temperature;
        b.maxTokens = maxTokens;
        b.extra.putAll(extra);
        return b;
    }

    public String name() {
        return name;
    }

    public Protocol protocol() {
        return protocol;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Duration connectTimeout() {
        return connectTimeout == null ? Duration.ofSeconds(15) : connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout == null ? Duration.ofMinutes(5) : readTimeout;
    }

    public Duration streamReadTimeout() {
        return streamReadTimeout == null ? Duration.ofMinutes(10) : streamReadTimeout;
    }

    public int maxRetries() {
        return Math.max(0, maxRetries);
    }

    public Double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    /** 实际下发给上游的模型 id：请求里显式指定则优先，否则用配置里的。 */
    public String resolveModel(com.benxin.llm.core.chat.ChatRequest request) {
        if (request != null && request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return model;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ModelConfig{" + name + ", " + protocol.id() + ", " + baseUrl + ", model=" + model + "}";
    }

    public static final class Builder {
        private final String name;
        private Protocol protocol = Protocol.OPENAI;
        private String baseUrl;
        private String apiKey;
        private String model;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration streamReadTimeout;
        private int maxRetries = 2;
        private Double temperature;
        private Integer maxTokens;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * 按 id 指定协议。
         *
         * <p>{@link Protocol#of(String)} 接受<b>任意</b>非空 id，因此这里不再对自定义协议名抛异常 ——
         * 该协议能不能用由 {@code ProtocolRegistry} 里有没有对应的 {@code ProtocolCodec} 决定，
         * 未注册时会在装配模型时给出可读报错并列出所有已注册协议。</p>
         *
         * <p><b>缺省 / 空白 = {@link Protocol#OPENAI}</b>：这个默认值属于
         * {@code llm.models.<key>.protocol} 这个<b>配置项</b>，因此由配置绑定层兜底，
         * 而不是由标识类型替调用方猜（见 F-46：标识类型里的"空 → openai"会让
         * 一个 {@code protocol()} 返回空串的 Codec 静默顶掉内置 openai）。</p>
         */
        public Builder protocol(String protocol) {
            this.protocol = protocol == null || protocol.isBlank()
                    ? Protocol.OPENAI
                    : Protocol.of(protocol);
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder header(String key, String value) {
            if (key != null && value != null) {
                this.headers.put(key, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> values) {
            if (values != null) {
                this.headers.putAll(values);
            }
            return this;
        }

        public Builder connectTimeout(Duration v) {
            this.connectTimeout = v;
            return this;
        }

        public Builder readTimeout(Duration v) {
            this.readTimeout = v;
            return this;
        }

        public Builder streamReadTimeout(Duration v) {
            this.streamReadTimeout = v;
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = v;
            return this;
        }

        public Builder temperature(Double v) {
            this.temperature = v;
            return this;
        }

        public Builder maxTokens(Integer v) {
            this.maxTokens = v;
            return this;
        }

        public Builder extra(String key, Object value) {
            if (key != null && value != null) {
                this.extra.put(key, value);
            }
            return this;
        }

        public Builder extra(Map<String, Object> values) {
            if (values != null) {
                this.extra.putAll(values);
            }
            return this;
        }

        public ModelConfig build() {
            return new ModelConfig(this);
        }
    }
}