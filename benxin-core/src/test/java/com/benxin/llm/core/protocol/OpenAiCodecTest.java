package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.protocol.openai.OpenAiCodec;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** OpenAI Chat Completions 协议的编解码契约。 */
class OpenAiCodecTest {

    private final OpenAiCodec codec = new OpenAiCodec();
    private final ModelConfig config = ModelConfig.builder("deepseek")
            .protocol(Protocol.OPENAI)
            .baseUrl("https://api.deepseek.com")
            .apiKey("sk-test")
            .model("deepseek-chat")
            .build();

    private static ToolSpec weatherTool() {
        return new ToolSpec("get_weather", "查询天气",
                Json.parse("""
                        {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""));
    }

    @Test
    @DisplayName("端点拼接对 base-url 的三种写法都成立")
    void buildsEndpoint() {
        assertThat(codec.endpoint(config, ChatRequest.builder().build()))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");

        ModelConfig withV1 = config.toBuilder().baseUrl("https://api.openai.com/v1").build();
        assertThat(codec.endpoint(withV1, ChatRequest.builder().build()))
                .isEqualTo("https://api.openai.com/v1/chat/completions");

        ModelConfig full = config.toBuilder().baseUrl("https://x.com/v1/chat/completions").build();
        assertThat(codec.endpoint(full, ChatRequest.builder().build()))
                .isEqualTo("https://x.com/v1/chat/completions");
    }

    @Test
    @DisplayName("认证头使用 Bearer 方案")
    void setsAuthHeader() {
        assertThat(codec.headers(config)).containsEntry("Authorization", "Bearer sk-test");
    }

    @Test
    @DisplayName("编码 system / user / assistant+tool_calls / tool 四类消息与工具声明")
    void encodesRequest() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是助手"),
                        ChatMessage.user("北京天气"),
                        ChatMessage.assistant("", List.of(new ToolUsePart("call_1", "get_weather", "{\"city\":\"北京\"}"))),
                        ChatMessage.toolResult("call_1", "get_weather", "晴 26 度", false)))
                .tools(List.of(weatherTool()))
                .toolChoice("auto")
                .temperature(0.5)
                .maxTokens(1024)
                .stream(true)
                .extra("top_k", 20)
                .build();

        JsonNode body = Json.parse(codec.encode(request, config));

        assertThat(body.get("model").asText()).isEqualTo("deepseek-chat");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.5);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1024);
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("top_k").asInt()).isEqualTo(20);   // extra 透传到顶层

        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(2).get("tool_calls").get(0).get("function").get("name").asText())
                .isEqualTo("get_weather");
        assertThat(messages.get(2).get("tool_calls").get(0).get("id").asText()).isEqualTo("call_1");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).get("tool_call_id").asText()).isEqualTo("call_1");

        JsonNode function = body.get("tools").get(0).get("function");
        assertThat(function.get("name").asText()).isEqualTo("get_weather");
        assertThat(function.get("parameters").get("type").asText()).isEqualTo("object");
    }

    @Test
    @DisplayName("多模态 user 消息编码成 content 数组")
    void encodesImagePart() {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user(
                        new com.benxin.llm.core.message.TextPart("这是什么"),
                        ImagePart.ofBase64("image/png", "AAAA")))
                .build();

        JsonNode content = Json.parse(codec.encode(request, config)).get("messages").get(0).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).get("image_url").get("url").asText()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("解码响应中的正文、工具调用、推理内容与用量")
    void decodesResponse() {
        String body = """
                {
                  "id": "chat-1",
                  "model": "deepseek-reasoner",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "我来帮你查。",
                      "reasoning_content": "先看天气工具",
                      "tool_calls": [{
                        "id": "call_abc",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"北京\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "prompt_tokens_details": {"cached_tokens": 64},
                    "completion_tokens_details": {"reasoning_tokens": 8}
                  }
                }
                """;
        ChatResponse response = codec.decode(body, config);

        assertThat(response.text()).isEqualTo("我来帮你查。");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.message().thinking()).isEqualTo("先看天气工具");
        assertThat(response.message().toolUses()).hasSize(1);
        assertThat(response.message().toolUses().get(0).name()).isEqualTo("get_weather");
        assertThat(response.message().toolUses().get(0).id()).isEqualTo("call_abc");
        assertThat(response.usage().inputTokens()).isEqualTo(100);
        assertThat(response.usage().outputTokens()).isEqualTo(20);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(64);
        assertThat(response.usage().reasoningTokens()).isEqualTo(8);
    }

    @Test
    @DisplayName("流式：按 index 拼装被分片的工具参数，并逐个完整回调")
    void assemblesStreamedToolArguments() {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"我"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"来查"}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"ci"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\\":\\"北京\\"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":30,"completion_tokens":9}}

                data: [DONE]

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.text()).isEqualTo("我来查");
        assertThat(collector.toolUses()).hasSize(1);
        assertThat(collector.toolUses().get(0).name()).isEqualTo("get_weather");
        assertThat(Json.parse(collector.toolUses().get(0).argumentsJson()).get("city").asText())
                .isEqualTo("北京");
        assertThat(collector.usage().inputTokens()).isEqualTo(30);

        ChatResponse aggregated = collector.response();
        assertThat(aggregated.message().text()).isEqualTo("我来查");
        assertThat(aggregated.message().toolUses()).hasSize(1);
    }

    @Test
    @DisplayName("流式：增量事件按到达顺序转发给下游")
    void forwardsDeltas() {
        StringBuilder deltas = new StringBuilder();
        LlmStreamHandler downstream = new LlmStreamHandler() {
            @Override
            public void onTextDelta(String delta) {
                deltas.append(delta);
            }
        };
        StreamCollector collector = new StreamCollector(downstream);
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse("""
                data: {"choices":[{"delta":{"content":"A"}}]}

                data: {"choices":[{"delta":{"content":"B"}}]}

                data: [DONE]

                """, decoder::accept);
        decoder.finish();

        assertThat(deltas.toString()).isEqualTo("AB");
    }

}
