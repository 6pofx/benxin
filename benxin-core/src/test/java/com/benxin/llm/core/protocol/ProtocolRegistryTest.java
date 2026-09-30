package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 协议扩展点（F-3）的验收：注册一个 {@link ProtocolCodec} 就能用自定义协议。
 *
 * <p>在此之前 {@code Protocol} 是封闭枚举、starter 零消费 {@code ProtocolCodec} bean、
 * {@code ModelFactory} 只认写死的三个分支 —— "加第四种协议"在第一行就断。
 * 这组测试钉住修复后的三件事：</p>
 * <ol>
 *   <li>{@link Protocol} 是可扩展标识类型，任意 id 都能构造；</li>
 *   <li>{@link ProtocolRegistry} 能把自定义 Codec 解析出来，{@link ModelFactory} 走注册表而非 switch；</li>
 *   <li>未注册的协议仍然<b>快速失败且报错可读</b>（列出所有可用协议 + 怎么加）。</li>
 * </ol>
 */
class ProtocolRegistryTest {

    /** 只记录、不外发的最小传输层。 */
    private static final class RecordingTransport implements HttpTransport {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
            calls.incrementAndGet();
            return TransportResponse.of(200, Map.of(), "{}");
        }

        @Override
        public TransportResponse postStreaming(String url, Map<String, String> headers, String body,
                                               Duration timeout) {
            calls.incrementAndGet();
            return TransportResponse.of(200, Map.of(), "{}");
        }
    }

    /** 一个"什么都不做"的自定义协议实现，用于验证它能被解析到。 */
    private static final class BedrockCodec implements ProtocolCodec {
        private final AtomicInteger endpoints = new AtomicInteger();

        @Override
        public Protocol protocol() {
            return Protocol.of("bedrock");
        }

        @Override
        public String endpoint(ModelConfig config, ChatRequest request) {
            endpoints.incrementAndGet();
            return "https://bedrock.internal/model/invoke";
        }

        @Override
        public Map<String, String> headers(ModelConfig config) {
            return Map.of("X-Custom", "1");
        }

        @Override
        public String encode(ChatRequest request, ModelConfig config) {
            return "{}";
        }

        @Override
        public ChatResponse decode(String responseBody, ModelConfig config) {
            return ChatResponse.builder()
                    .message(ChatMessage.assistant("来自 bedrock"))
                    .finishReason(FinishReason.STOP)
                    .build();
        }

        @Override
        public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
            return new StreamDecoder() {
                @Override
                public void accept(SseEvent event) {
                }

                @Override
                public void finish() {
                }
            };
        }
    }

    // ------------------------------------------------------------------
    // Protocol 本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Protocol 不再是封闭枚举：任意 id 都能构造，且按 id 驻留")
    void protocolAcceptsAnyIdAndInterns() {
        assertThat(Protocol.of("bedrock").id()).isEqualTo("bedrock");
        // 驻留：同一 id 拿到同一实例，因此 == 也成立
        assertThat(Protocol.of("bedrock")).isSameAs(Protocol.of("bedrock"));
        assertThat(Protocol.of("openai")).isSameAs(Protocol.OPENAI);
        assertThat(Protocol.of("OpenAI")).as("大小写不敏感").isSameAs(Protocol.OPENAI);
        assertThat(Protocol.of("  gemini  ")).as("首尾空白被归一").isSameAs(Protocol.GEMINI);
        // 归一化不替换分隔符，否则自定义 id 会被悄悄改写
        assertThat(Protocol.of("my-protocol").id()).isEqualTo("my-protocol");
        assertThat(Protocol.of("my_protocol").id()).isEqualTo("my_protocol");
        // equals/hashCode 按 id
        assertThat(Protocol.of("bedrock")).isEqualTo(Protocol.of("bedrock"));
        assertThat(Protocol.of("bedrock")).hasSameHashCodeAs(Protocol.of("bedrock"));
    }

    @Test
    @DisplayName("空值回落到 openai；from() 作为别名保留且语义与 of() 一致")
    void blankFallsBackToOpenAi() {
        assertThat(Protocol.of(null)).isSameAs(Protocol.OPENAI);
        assertThat(Protocol.of("")).isSameAs(Protocol.OPENAI);
        assertThat(Protocol.from("anthropic")).isSameAs(Protocol.ANTHROPIC);
        // 语义变更点：从前 from("bedrock") 会抛，现在不会
        assertThat(Protocol.from("bedrock").id()).isEqualTo("bedrock");
    }

    @Test
    @DisplayName("内置协议是四个：openai / anthropic / gemini / responses")
    void builtinsAreFour() {
        assertThat(Protocol.builtins()).containsExactly(
                Protocol.OPENAI, Protocol.ANTHROPIC, Protocol.GEMINI, Protocol.RESPONSES);
        assertThat(Protocol.builtinIds()).containsExactly("openai", "anthropic", "gemini", "responses");
        assertThat(Protocol.isBuiltin(Protocol.RESPONSES)).isTrue();
        assertThat(Protocol.isBuiltin(Protocol.of("bedrock"))).isFalse();
    }

    @Test
    @DisplayName("ModelConfig 现在接受自定义协议名，不再在绑定期抛异常")
    void modelConfigAcceptsCustomProtocolName() {
        ModelConfig config = ModelConfig.builder("bx")
                .protocol("bedrock")
                .baseUrl("https://bedrock.internal")
                .build();
        assertThat(config.protocol().id()).isEqualTo("bedrock");
    }

    // ------------------------------------------------------------------
    // 注册表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("默认注册表预置四个内置协议，且都能解析出编解码器")
    void builtinRegistryResolvesAllFour() {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();

        assertThat(registry.ids()).containsExactly("openai", "anthropic", "gemini", "responses");
        for (Protocol protocol : Protocol.builtins()) {
            assertThat(registry.find(protocol))
                    .as("内置协议 %s 应当可解析", protocol.id())
                    .isPresent();
        }
        assertThat(registry.find(Protocol.RESPONSES).orElseThrow().protocol())
                .isSameAs(Protocol.RESPONSES);
    }

    @Test
    @DisplayName("空注册表什么也解析不出来 —— 内置协议不是硬编码在解析路径里的")
    void emptyRegistryResolvesNothing() {
        ProtocolRegistry registry = ProtocolRegistry.empty();
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.find(Protocol.OPENAI)).isEmpty();
    }

    @Test
    @DisplayName("注册自定义 Codec 后，按该协议装配模型会用到它 —— 这是 F-3 的核心断言")
    void customCodecIsUsedWhenAssemblingModel() {
        BedrockCodec codec = new BedrockCodec();
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins().register(codec);
        RecordingTransport transport = new RecordingTransport();

        ModelConfig config = ModelConfig.builder("bedrock-endpoint")
                .protocol("bedrock")
                .baseUrl("https://ignored.invalid")
                .model("anthropic.claude-v2")
                .maxRetries(0)   // 关掉自动重试，便于断言拿到的是裸模型
                .build();

        LlmModel model = ModelFactory.create(config, transport, registry);

        assertThat(model).isInstanceOf(HttpLlmModel.class);
        assertThat(((HttpLlmModel) model).codec()).isSameAs(codec);
        assertThat(model.name()).isEqualTo("bedrock-endpoint");
        assertThat(registry.ids()).contains("bedrock");

        // 能力声明来自 Codec（自定义协议不必再写一个模型类）
        assertThat(model.capabilities()).isEqualTo(codec.capabilities());
    }

    @Test
    @DisplayName("同名注册覆盖内置：自定义 Codec 可以顶掉 openai")
    void customCodecCanOverrideBuiltin() {
        ProtocolCodec override = new ProtocolCodec() {
            @Override
            public Protocol protocol() {
                return Protocol.OPENAI;
            }

            @Override
            public String endpoint(ModelConfig config, ChatRequest request) {
                return "https://my-gateway.internal/v1/chat/completions";
            }

            @Override
            public Map<String, String> headers(ModelConfig config) {
                return Map.of();
            }

            @Override
            public String encode(ChatRequest request, ModelConfig config) {
                return "{}";
            }

            @Override
            public ChatResponse decode(String responseBody, ModelConfig config) {
                return ChatResponse.builder().message(ChatMessage.assistant("")).build();
            }

            @Override
            public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
                return new StreamDecoder() {
                    @Override
                    public void accept(SseEvent event) {
                    }

                    @Override
                    public void finish() {
                    }
                };
            }
        };

        ProtocolRegistry registry = ProtocolRegistry.withBuiltins().register(override);
        LlmModel model = ModelFactory.create(ModelConfig.builder("m")
                .protocol("openai")
                .baseUrl("https://api.openai.com")
                .maxRetries(0)
                .build(), new RecordingTransport(), registry);

        assertThat(((HttpLlmModel) model).codec()).isSameAs(override);
    }

    @Test
    @DisplayName("未注册的协议仍然快速失败，报错列出所有可用协议并说明怎么加")
    void unregisteredProtocolFailsReadably() {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();
        ModelConfig config = ModelConfig.builder("bedrock-endpoint")
                .protocol("bedrock")
                .baseUrl("https://bedrock.internal")
                .maxRetries(0)
                .build();

        assertThatThrownBy(() -> ModelFactory.create(config, new RecordingTransport(), registry))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("未知协议")
                .hasMessageContaining("bedrock")
                // 可读性：不必翻源码就知道有哪些可选
                .hasMessageContaining("openai")
                .hasMessageContaining("anthropic")
                .hasMessageContaining("gemini")
                .hasMessageContaining("responses")
                // 以及"怎么把自己那个加进去"
                .hasMessageContaining("ProtocolCodec");
    }

    @Test
    @DisplayName("ProtocolCodec.capabilities() 有默认值，自定义协议可以不实现它")
    void capabilitiesHasDefault() {
        BedrockCodec codec = new BedrockCodec();
        assertThat(codec.capabilities()).isNotNull();
        assertThat(codec.capabilities().streaming()).isTrue();
        assertThat(codec.capabilities().toolCalling()).isTrue();
    }
}
