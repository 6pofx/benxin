package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.protocol.responses.ResponsesApiCodec;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI Responses API 协议的编解码契约。
 *
 * <p>重点钉住它与 Chat Completions 的<b>结构性差异</b> —— 这些恰是照抄 Chat Completions
 * 实现时最容易写错的地方：顶层 {@code instructions}、{@code input} item 列表、
 * 扁平的 {@code tools[]}、{@code max_output_tokens}、{@code status} 而非 {@code finish_reason}、
 * 以及<b>没有 {@code [DONE]}</b> 的命名 SSE 事件流。</p>
 */
class ResponsesApiCodecTest {

    private final ResponsesApiCodec codec = new ResponsesApiCodec();
    private final ModelConfig config = ModelConfig.builder("openai-responses")
            .protocol(Protocol.RESPONSES)
            .baseUrl("https://api.openai.com")
            .apiKey("sk-test")
            .model("gpt-4o")
            .maxRetries(0)
            .build();

    private static ToolSpec weatherTool() {
        return new ToolSpec("get_weather", "查询天气",
                Json.parse("""
                        {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""));
    }

    // ------------------------------------------------------------------
    // 端点与认证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("端点拼接：域名 / 带版本段 / 完整路径三种 base-url 写法都成立")
    void buildsEndpoint() {
        assertThat(codec.endpoint(config, ChatRequest.builder().build()))
                .isEqualTo("https://api.openai.com/v1/responses");

        ModelConfig withV1 = config.toBuilder().baseUrl("https://api.openai.com/v1").build();
        assertThat(codec.endpoint(withV1, ChatRequest.builder().build()))
                .isEqualTo("https://api.openai.com/v1/responses");

        // 挂在无版本段路径上的厂商：把完整地址填进 base-url 即可
        ModelConfig full = config.toBuilder().baseUrl("https://api.deepseek.com/responses").build();
        assertThat(codec.endpoint(full, ChatRequest.builder().build()))
                .isEqualTo("https://api.deepseek.com/responses");

        // 尾部斜杠不应拼出双斜杠
        ModelConfig slash = config.toBuilder().baseUrl("https://api.openai.com/v1/").build();
        assertThat(codec.endpoint(slash, ChatRequest.builder().build()))
                .isEqualTo("https://api.openai.com/v1/responses");
    }

    @Test
    @DisplayName("认证头与 Chat Completions 一致，且自定义头可覆盖")
    void setsAuthHeaders() {
        assertThat(codec.headers(config)).containsEntry("Authorization", "Bearer sk-test");

        ModelConfig withHeader = config.toBuilder().header("OpenAI-Beta", "responses=v1").build();
        assertThat(codec.headers(withHeader)).containsEntry("OpenAI-Beta", "responses=v1");
    }

    // ------------------------------------------------------------------
    // 请求编码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("system 提到顶层 instructions；对话走 input item 列表")
    void encodesInstructionsAndInput() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是助手"),
                        ChatMessage.system("回答要简洁"),
                        ChatMessage.user("你好")))
                .build();

        JsonNode body = Json.parse(codec.encode(request, config));

        assertThat(body.get("instructions").asText()).contains("你是助手").contains("回答要简洁");
        // Chat Completions 的痕迹不该出现
        assertThat(body.has("messages")).isFalse();

        JsonNode input = body.get("input");
        assertThat(input).hasSize(1);
        assertThat(input.get(0).get("type").asText()).isEqualTo("message");
        assertThat(input.get(0).get("role").asText()).isEqualTo("user");
        assertThat(input.get(0).get("content").asText()).isEqualTo("你好");
    }

    @Test
    @DisplayName("工具调用与工具结果是独立 item，不是消息上的字段")
    void encodesToolCallsAsStandaloneItems() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("北京天气"),
                        ChatMessage.assistant("我查一下", List.of(
                                new ToolUsePart("call_1", "get_weather", "{\"city\":\"北京\"}"))),
                        ChatMessage.toolResult("call_1", "get_weather", "晴 26 度", false)))
                .build();

        JsonNode input = Json.parse(codec.encode(request, config)).get("input");

        // user / assistant 文本 / function_call / function_call_output
        assertThat(input).hasSize(4);
        assertThat(input.get(0).get("role").asText()).isEqualTo("user");

        JsonNode assistant = input.get(1);
        assertThat(assistant.get("type").asText()).isEqualTo("message");
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").asText()).isEqualTo("我查一下");
        // Chat Completions 会把工具调用挂在这里；Responses 不会
        assertThat(assistant.has("tool_calls")).isFalse();

        JsonNode call = input.get(2);
        assertThat(call.get("type").asText()).isEqualTo("function_call");
        assertThat(call.get("call_id").asText()).isEqualTo("call_1");
        assertThat(call.get("name").asText()).isEqualTo("get_weather");
        // arguments 是 JSON 文本而非对象
        assertThat(call.get("arguments").isTextual()).isTrue();

        JsonNode output = input.get(3);
        assertThat(output.get("type").asText()).isEqualTo("function_call_output");
        assertThat(output.get("call_id").asText()).isEqualTo("call_1");
        assertThat(output.get("output").asText()).isEqualTo("晴 26 度");
    }

    @Test
    @DisplayName("tools[] 是扁平的：name/parameters 直接在 tool 上，没有 function 包装层")
    void encodesToolsFlat() {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user("北京天气"))
                .tools(List.of(weatherTool()))
                .build();

        JsonNode tool = Json.parse(codec.encode(request, config)).get("tools").get(0);

        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("name").asText()).isEqualTo("get_weather");
        assertThat(tool.get("parameters").get("properties").has("city")).isTrue();
        // Chat Completions 的形态在这里是错的
        assertThat(tool.has("function")).isFalse();
    }

    @Test
    @DisplayName("tool_choice：关键字直接下发，具体工具名用 {type,name}")
    void encodesToolChoice() {
        ChatRequest.Builder base = ChatRequest.builder().message(ChatMessage.user("x"));

        assertThat(Json.parse(codec.encode(base.toolChoice("auto").build(), config))
                .get("tool_choice").asText()).isEqualTo("auto");
        assertThat(Json.parse(codec.encode(base.toolChoice("required").build(), config))
                .get("tool_choice").asText()).isEqualTo("required");
        assertThat(Json.parse(codec.encode(base.toolChoice("none").build(), config))
                .get("tool_choice").asText()).isEqualTo("none");

        JsonNode named = Json.parse(codec.encode(base.toolChoice("get_weather").build(), config))
                .get("tool_choice");
        assertThat(named.get("type").asText()).isEqualTo("function");
        assertThat(named.get("name").asText()).isEqualTo("get_weather");
        assertThat(named.has("function")).as("不是 Chat Completions 的嵌套形态").isFalse();
    }

    @Test
    @DisplayName("采样参数用 max_output_tokens；extra 覆盖式写入顶层")
    void encodesGenerationParams() {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user("x"))
                .temperature(0.7)
                .topP(0.9)
                .maxTokens(1024)
                .stream(true)
                .extra("reasoning", Json.parse("{\"effort\":\"high\"}"))
                .build();

        JsonNode body = Json.parse(codec.encode(request, config));

        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(body.get("max_output_tokens").asInt()).isEqualTo(1024);
        assertThat(body.has("max_tokens")).as("Responses 不认 max_tokens").isFalse();
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("reasoning").get("effort").asText()).isEqualTo("high");
    }

    @Test
    @DisplayName("多模态：图片编码成 input_image 内容块，纯文本仍用字符串形态")
    void encodesImages() {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.user(
                        new TextPart("这是什么"),
                        ImagePart.ofBase64("image/png", "AAAA")))
                .build();

        JsonNode content = Json.parse(codec.encode(request, config)).get("input").get(0).get("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("input_text");
        assertThat(content.get(0).get("text").asText()).isEqualTo("这是什么");
        assertThat(content.get(1).get("type").asText()).isEqualTo("input_image");
        assertThat(content.get(1).get("image_url").asText()).startsWith("data:image/png;base64,");
    }

    // ------------------------------------------------------------------
    // 响应解码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("解析 output[]：message 取正文、function_call 取工具调用、reasoning 取思维链")
    void decodesOutputItems() {
        String body = """
                {
                  "id": "resp_1",
                  "object": "response",
                  "model": "gpt-4o",
                  "status": "completed",
                  "output": [
                    {"type": "reasoning", "summary": [{"type": "summary_text", "text": "先看天气"}]},
                    {"type": "message", "role": "assistant", "status": "completed",
                     "content": [{"type": "output_text", "text": "我来查询。", "annotations": []}]},
                    {"type": "function_call", "id": "fc_1", "call_id": "call_abc",
                     "name": "get_weather", "arguments": "{\\"city\\":\\"北京\\"}", "status": "completed"}
                  ],
                  "usage": {
                    "input_tokens": 100, "output_tokens": 20, "total_tokens": 120,
                    "input_tokens_details": {"cached_tokens": 64},
                    "output_tokens_details": {"reasoning_tokens": 8}
                  }
                }
                """;
        ChatResponse response = codec.decode(body, config);

        assertThat(response.text()).isEqualTo("我来查询。");
        assertThat(response.message().thinking()).isEqualTo("先看天气");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.message().toolUses()).hasSize(1);
        assertThat(response.message().toolUses().get(0).id()).isEqualTo("call_abc");
        assertThat(response.message().toolUses().get(0).name()).isEqualTo("get_weather");
        assertThat(Json.parse(response.message().toolUses().get(0).argumentsJson()).get("city").asText())
                .isEqualTo("北京");
        assertThat(response.usage().inputTokens()).isEqualTo(100);
        assertThat(response.usage().outputTokens()).isEqualTo(20);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(64);
        assertThat(response.usage().reasoningTokens()).isEqualTo(8);
    }

    @Test
    @DisplayName("status 映射：completed → STOP，incomplete → LENGTH，failed → ERROR")
    void mapsStatusToFinishReason() {
        assertThat(codec.decode(responseWith("\"completed\""), config).finishReason())
                .isEqualTo(FinishReason.STOP);
        assertThat(codec.decode(responseWith("\"incomplete\""), config).finishReason())
                .isEqualTo(FinishReason.LENGTH);
        assertThat(codec.decode(responseWith("\"failed\""), config).finishReason())
                .isEqualTo(FinishReason.ERROR);
    }

    private String responseWith(String status) {
        return """
                {"id":"r","model":"gpt-4o","status":%s,
                 "output":[{"type":"message","role":"assistant",
                            "content":[{"type":"output_text","text":"答案"}]}],
                 "usage":{"input_tokens":1,"output_tokens":2}}
                """.formatted(status);
    }

    @Test
    @DisplayName("兼容端点只给 output_text 便捷字段时也能解出正文")
    void fallsBackToOutputTextField() {
        String body = """
                {"id":"r","status":"completed","output":[],"output_text":"便捷字段的回答",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;
        assertThat(codec.decode(body, config).text()).isEqualTo("便捷字段的回答");
    }

    @Test
    @DisplayName("错误信封与不可解析响应都给出可读错误，而不是静默空回答")
    void failsReadably() {
        assertThatThrownBy(() -> codec.decode(
                "{\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\"}}", config))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("model not found")
                .hasMessageContaining("invalid_request_error");

        assertThatThrownBy(() -> codec.decode("not json", config))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("不是合法 JSON");

        assertThatThrownBy(() -> codec.decode("{\"id\":\"r\",\"output\":[]}", config))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("output");
    }

    // ------------------------------------------------------------------
    // 流式解码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("流式：命名事件逐帧推进，response.completed 收尾并带回用量")
    void decodesStreamWithNamedEvents() {
        String sse = """
                event: response.created
                data: {"type":"response.created","response":{"id":"resp_9","model":"gpt-4o","status":"in_progress"}}

                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[]}}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"delta":"北京"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"delta":"晴 26 度"}

                event: response.output_text.done
                data: {"type":"response.output_text.done","item_id":"msg_1","output_index":0,"text":"北京晴 26 度"}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_9","model":"gpt-4o","status":"completed","usage":{"input_tokens":30,"output_tokens":9,"input_tokens_details":{"cached_tokens":12}}}}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.text()).isEqualTo("北京晴 26 度");
        assertThat(collector.usage().inputTokens()).isEqualTo(30);
        assertThat(collector.usage().outputTokens()).isEqualTo(9);
        assertThat(collector.usage().cachedInputTokens()).isEqualTo(12);
        assertThat(collector.response().finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    @DisplayName("流式：工具参数按 output_index 分片拼装，output_item.done 时回调完整调用")
    void assemblesStreamedFunctionCall() {
        String sse = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_7","name":"get_weather","arguments":""}}

                event: response.function_call_arguments.delta
                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\\"ci"}

                event: response.function_call_arguments.delta
                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"ty\\":\\"上海\\"}"}

                event: response.function_call_arguments.done
                data: {"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,"arguments":"{\\"city\\":\\"上海\\"}"}

                event: response.output_item.done
                data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_7","name":"get_weather","arguments":"{\\"city\\":\\"上海\\"}"}}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":10,"output_tokens":5}}}

                """;
        List<String> toolNames = new ArrayList<>();
        StreamCollector collector = new StreamCollector(new LlmStreamHandler() {
            @Override
            public void onToolCall(ToolUsePart toolUse) {
                toolNames.add(toolUse.name());
            }
        });
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(toolNames).as("工具调用恰好回调一次").containsExactly("get_weather");
        assertThat(collector.toolUses()).hasSize(1);
        assertThat(collector.toolUses().get(0).id()).isEqualTo("call_7");
        assertThat(Json.parse(collector.toolUses().get(0).argumentsJson()).get("city").asText())
                .isEqualTo("上海");
        assertThat(collector.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @DisplayName("流式：没有 [DONE]，靠 finish() 兜底收尾（网关提前断流也不吞掉工具调用）")
    void finishIsTheOnlyTerminator() {
        String sse = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_x","name":"get_time","arguments":""}}

                event: response.function_call_arguments.delta
                data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        // 流到这里就断了（没有 response.completed）—— finish() 必须把已累积的工具调用交出去
        decoder.finish();

        assertThat(collector.toolUses()).hasSize(1);
        assertThat(collector.toolUses().get(0).name()).isEqualTo("get_time");
        assertThat(collector.response()).isNotNull();
    }

    @Test
    @DisplayName("流式：response.failed 上报错误且不再补发 onComplete")
    void failedStreamReportsErrorOnly() {
        String sse = """
                event: response.failed
                data: {"type":"response.failed","response":{"id":"resp_x","status":"failed","error":{"code":"server_error","message":"上游炸了"}}}

                """;
        List<Throwable> errors = new ArrayList<>();
        List<ChatResponse> completes = new ArrayList<>();
        StreamCollector collector = new StreamCollector(new LlmStreamHandler() {
            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }

            @Override
            public void onComplete(ChatResponse response) {
                completes.add(response);
            }
        });
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).hasMessageContaining("上游炸了");
        assertThat(completes).as("失败后补发 onComplete 会让上层把半截响应当成功").isEmpty();
    }

    @Test
    @DisplayName("流式：网关吃掉 event: 行时靠 payload 的 type 仍能解码")
    void worksWithoutEventLines() {
        String sse = """
                data: {"type":"response.output_text.delta","output_index":0,"delta":"只有 data 行"}

                data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":3,"output_tokens":4}}}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.text()).isEqualTo("只有 data 行");
        assertThat(collector.usage().inputTokens()).isEqualTo(3);
    }

    @Test
    @DisplayName("流式：只发 done 不发 delta 的网关不会导致空回答")
    void toleratesDoneWithoutDeltas() {
        String sse = """
                event: response.output_text.done
                data: {"type":"response.output_text.done","output_index":0,"text":"整段文本一次给出"}

                event: response.completed
                data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":2}}}

                """;
        StreamCollector collector = new StreamCollector();
        StreamDecoder decoder = codec.newStreamDecoder(config, collector);
        SseParser.parse(sse, decoder::accept);
        decoder.finish();

        assertThat(collector.text()).isEqualTo("整段文本一次给出");
    }
}
