package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.protocol.gemini.GeminiCodec;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Google Gemini 协议的编解码契约。 */
class GeminiCodecTest {

    private final GeminiCodec codec = new GeminiCodec();
    private final ModelConfig config = ModelConfig.builder("gemini")
            .protocol(Protocol.GEMINI)
            .baseUrl("https://generativelanguage.googleapis.com")
            .apiKey("AIza-test")
            .model("gemini-2.5-pro")
            .build();

    @Test
    @DisplayName("非流式与流式走不同的方法后缀")
    void buildsEndpoint() {
        assertThat(codec.endpoint(config, ChatRequest.builder().build()))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent");
        assertThat(codec.endpoint(config, ChatRequest.builder().stream(true).build()))
                .contains(":streamGenerateContent")
                .contains("alt=sse");
    }

    @Test
    @DisplayName("密钥优先用请求头传递")
    void setsApiKeyHeader() {
        assertThat(codec.headers(config)).containsEntry("x-goog-api-key", "AIza-test");
    }

    @Test
    @DisplayName("system 走 systemInstruction，assistant 角色映射为 model")
    void encodesSystemAndRoles() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是助手"),
                        ChatMessage.user("你好"),
                        ChatMessage.assistant("你好，有什么可以帮你？"),
                        ChatMessage.user("讲个笑话")))
                .build();

        JsonNode body = Json.parse(codec.encode(request, config));

        assertThat(body.get("systemInstruction").get("parts").get(0).get("text").asText())
                .isEqualTo("你是助手");
        JsonNode contents = body.get("contents");
        assertThat(contents.get(0).get("role").asText()).isEqualTo("user");
        assertThat(contents.get(1).get("role").asText()).isEqualTo("model");
    }

    @Test
    @DisplayName("工具声明放进 functionDeclarations，且 JSON Schema 必须被清洗")
    void sanitizesSchema() {
        // 故意带上 Gemini 不接受的字段
        ToolSpec tool = new ToolSpec("get_weather", "查询天气", Json.parse("""
                {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
                 "properties":{"city":{"type":"string","title":"城市"}},
                 "required":["city"],"additionalProperties":false}"""));

        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user("北京天气"))
                .tools(List.of(tool))
                .build();

        JsonNode declaration = Json.parse(codec.encode(request, config))
                .get("tools").get(0).get("functionDeclarations").get(0);

        assertThat(declaration.get("name").asText()).isEqualTo("get_weather");
        JsonNode parameters = declaration.get("parameters");
        assertThat(parameters.has("additionalProperties")).isFalse();
        assertThat(parameters.has("$schema")).isFalse();
        assertThat(parameters.get("properties").get("city").has("title")).isFalse();
        assertThat(parameters.get("properties").get("city").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("generationConfig 承载采样参数")
    void encodesGenerationConfig() {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user("hi"))
                .temperature(0.7)
                .maxTokens(256)
                .build();

        JsonNode generation = Json.parse(codec.encode(request, config)).get("generationConfig");
        assertThat(generation.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(generation.get("maxOutputTokens").asInt()).isEqualTo(256);
    }

    @Test
    @DisplayName("工具结果用 functionResponse 且按工具名对位")
    void encodesToolResultByName() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("北京天气"),
                        ChatMessage.assistant("", List.of(
                                new ToolUsePart("call_0", "get_weather", "{\"city\":\"北京\"}"))),
                        ChatMessage.toolResult("call_0", "get_weather", "晴 26 度", false)))
                .build();

        JsonNode contents = Json.parse(codec.encode(request, config)).get("contents");
        JsonNode functionCall = contents.get(1).get("parts").get(0).get("functionCall");
        assertThat(functionCall.get("name").asText()).isEqualTo("get_weather");
        assertThat(functionCall.get("args").get("city").asText()).isEqualTo("北京");

        // 工具结果必须由 user 侧发出
        JsonNode last = contents.get(contents.size() - 1);
        assertThat(last.get("role").asText()).isEqualTo("user");
        JsonNode functionResponse = last.get("parts").get(0).get("functionResponse");
        assertThat(functionResponse.get("name").asText()).isEqualTo("get_weather");
    }

    @Test
    @DisplayName("解码 parts、finishReason 与 usageMetadata")
    void decodesResponse() {
        String body = """
                {
                  "candidates": [{
                    "content": {
                      "role": "model",
                      "parts": [
                        {"text": "正在查询。"},
                        {"functionCall": {"name": "get_weather", "args": {"city": "北京"}}}
                      ]
                    },
                    "finishReason": "STOP"
                  }],
                  "usageMetadata": {
                    "promptTokenCount": 88,
                    "candidatesTokenCount": 12,
                    "cachedContentTokenCount": 32
                  }
                }
                """;
        ChatResponse response = codec.decode(body, config);

        assertThat(response.text()).isEqualTo("正在查询。");
        assertThat(response.message().toolUses()).hasSize(1);
        assertThat(response.message().toolUses().get(0).name()).isEqualTo("get_weather");
        assertThat(Json.parse(response.message().toolUses().get(0).argumentsJson()).get("city").asText())
                .isEqualTo("北京");
        assertThat(response.usage().inputTokens()).isEqualTo(88);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(32);
    }

    @Test
    @DisplayName("安全拦截映射为 CONTENT_FILTER")
    void mapsSafetyBlock() {
        String body = """
                {"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}
                """;
        ChatResponse response = codec.decode(body, config);
        assertThat(response.finishReason()).isEqualTo(FinishReason.CONTENT_FILTER);
    }

    @Test
    @DisplayName("流式：逐 chunk 累积文本与工具调用")
    void decodesStream() {
        String sse = """
                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"北京"}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"晴 26 度"}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"北京"}}}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":7}}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.text()).isEqualTo("北京晴 26 度");
        assertThat(collector.toolUses()).hasSize(1);
        assertThat(collector.toolUses().get(0).name()).isEqualTo("get_weather");
        assertThat(collector.usage().inputTokens()).isEqualTo(20);
    }
}