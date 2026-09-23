package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.protocol.anthropic.AnthropicCodec;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Anthropic Messages 协议的编解码契约。 */
class AnthropicCodecTest {

    private final AnthropicCodec codec = new AnthropicCodec();
    private final ModelConfig config = ModelConfig.builder("claude")
            .protocol(Protocol.ANTHROPIC)
            .baseUrl("https://api.anthropic.com")
            .apiKey("sk-ant-test")
            .model("claude-sonnet-4-5")
            .build();

    @Test
    @DisplayName("端点与 base-url 去重")
    void buildsEndpoint() {
        assertThat(codec.endpoint(config, ChatRequest.builder().build()))
                .isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(codec.endpoint(config.toBuilder().baseUrl("https://proxy/v1").build(),
                ChatRequest.builder().build())).isEqualTo("https://proxy/v1/messages");
    }

    @Test
    @DisplayName("认证使用 x-api-key，且必须带 anthropic-version")
    void setsHeaders() {
        assertThat(codec.headers(config))
                .containsEntry("x-api-key", "sk-ant-test")
                .containsKey("anthropic-version");
    }

    @Test
    @DisplayName("system 提到顶层，其余消息用 content block 表达")
    void encodesSystemAtTopLevel() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是助手"),
                        ChatMessage.system("回答要简洁"),
                        ChatMessage.user("你好")))
                .maxTokens(2048)
                .build();

        JsonNode body = Json.parse(codec.encode(request, config));

        assertThat(body.get("system").asText()).contains("你是助手").contains("回答要简洁");
        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(2048);
    }

    @Test
    @DisplayName("max_tokens 是必填项，未指定时必须有兜底值")
    void maxTokensIsMandatory() {
        JsonNode body = Json.parse(codec.encode(
                ChatRequest.builder().message(ChatMessage.user("hi")).build(), config));
        assertThat(body.has("max_tokens")).isTrue();
        assertThat(body.get("max_tokens").asInt()).isPositive();
    }

    @Test
    @DisplayName("工具声明使用 input_schema 字段名")
    void encodesToolsWithInputSchema() {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user("北京天气"))
                .tools(List.of(new ToolSpec("get_weather", "查询天气",
                        Json.parse("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"))))
                .build();

        JsonNode tool = Json.parse(codec.encode(request, config)).get("tools").get(0);
        assertThat(tool.get("name").asText()).isEqualTo("get_weather");
        assertThat(tool.has("input_schema")).isTrue();
        assertThat(tool.get("input_schema").get("properties").has("city")).isTrue();
    }

    @Test
    @DisplayName("连续的工具结果必须合并进同一个 user 回合（Anthropic 硬约束）")
    void mergesConsecutiveToolResultsIntoOneUserTurn() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("查两个城市的天气"),
                        ChatMessage.assistant("", List.of(
                                new ToolUsePart("t1", "get_weather", "{\"city\":\"北京\"}"),
                                new ToolUsePart("t2", "get_weather", "{\"city\":\"上海\"}"))),
                        ChatMessage.toolResult("t1", "get_weather", "北京晴 26 度", false),
                        ChatMessage.toolResult("t2", "get_weather", "上海多云 24 度", false)))
                .build();

        JsonNode messages = Json.parse(codec.encode(request, config)).get("messages");

        // 期望：user / assistant(tool_use x2) / user(tool_result x2) —— 共 3 条
        assertThat(messages).hasSize(3);
        JsonNode last = messages.get(2);
        assertThat(last.get("role").asText()).isEqualTo("user");
        assertThat(last.get("content")).hasSize(2);
        assertThat(last.get("content").get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(last.get("content").get(0).get("tool_use_id").asText()).isEqualTo("t1");
        assertThat(last.get("content").get(1).get("tool_use_id").asText()).isEqualTo("t2");
    }

    @Test
    @DisplayName("assistant 的 tool_use 参数是对象而非字符串")
    void encodesToolUseAsObject() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("x"),
                        ChatMessage.assistant("", List.of(
                                new ToolUsePart("t1", "get_weather", "{\"city\":\"北京\"}")))))
                .build();

        JsonNode block = Json.parse(codec.encode(request, config))
                .get("messages").get(1).get("content").get(0);
        assertThat(block.get("type").asText()).isEqualTo("tool_use");
        assertThat(block.get("input").isObject()).isTrue();
        assertThat(block.get("input").get("city").asText()).isEqualTo("北京");
    }

    @Test
    @DisplayName("解码 content block 数组与 stop_reason")
    void decodesResponse() {
        String body = """
                {
                  "id": "msg_1",
                  "model": "claude-sonnet-4-5",
                  "content": [
                    {"type": "text", "text": "我来查询。"},
                    {"type": "tool_use", "id": "toolu_1", "name": "get_weather",
                     "input": {"city": "北京"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 120, "output_tokens": 33, "cache_read_input_tokens": 64}
                }
                """;
        ChatResponse response = codec.decode(body, config);

        assertThat(response.text()).isEqualTo("我来查询。");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.message().toolUses()).hasSize(1);
        assertThat(response.message().toolUses().get(0).id()).isEqualTo("toolu_1");
        assertThat(Json.parse(response.message().toolUses().get(0).argumentsJson()).get("city").asText())
                .isEqualTo("北京");
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(64);
    }

    @Test
    @DisplayName("解码 thinking block 并保留 signature")
    void decodesThinkingBlock() {
        String body = """
                {
                  "content": [
                    {"type": "thinking", "thinking": "先想想", "signature": "sig-1"},
                    {"type": "text", "text": "答案"}
                  ],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 1, "output_tokens": 1}
                }
                """;
        ChatResponse response = codec.decode(body, config);

        assertThat(response.message().thinking()).isEqualTo("先想想");
        assertThat(response.text()).isEqualTo("答案");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    @DisplayName("流式：input_json_delta 分片拼装成完整工具调用")
    void assemblesInputJsonDelta() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","model":"claude","usage":{"input_tokens":50,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"我来"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"get_weather"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"ci"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ty\\":\\"上海\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.text()).isEqualTo("我来");
        assertThat(collector.toolUses()).hasSize(1);
        assertThat(collector.toolUses().get(0).name()).isEqualTo("get_weather");
        assertThat(collector.toolUses().get(0).id()).isEqualTo("toolu_9");
        assertThat(Json.parse(collector.toolUses().get(0).argumentsJson()).get("city").asText())
                .isEqualTo("上海");

        ChatResponse aggregated = collector.response();
        assertThat(aggregated.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(aggregated.usage().inputTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("流式：thinking_delta 增量归入思维链")
    void handlesThinkingDelta() {
        String sse = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"推理中"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.thinkingText()).isEqualTo("推理中");
    }
}