package com.benxin.llm.core.protocol.responses;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OpenAI Responses API 协议编解码器（{@code POST /v1/responses}）。
 *
 * <p>它与 {@code OpenAiCodec}（Chat Completions）常被误认为"同一个 API 的两种调用方式"，
 * 实际是<b>两套不同的报文结构</b>，因此在本心里是并列的两个协议而不是一个开关：</p>
 *
 * <table border="1">
 *   <caption>两套协议的关键差异</caption>
 *   <tr><th></th><th>Chat Completions</th><th>Responses API</th></tr>
 *   <tr><td>系统提示</td><td>messages 里的 system 角色</td><td><b>顶层 {@code instructions}</b></td></tr>
 *   <tr><td>对话载体</td><td>{@code messages[]}</td><td><b>{@code input[]} item 列表</b></td></tr>
 *   <tr><td>工具声明</td><td>{@code tools[].function.{name,parameters}}</td>
 *       <td><b>{@code tools[].{name,parameters}}（扁平，无 function 层）</b></td></tr>
 *   <tr><td>工具调用</td><td>assistant 消息里的 {@code tool_calls[]}</td>
 *       <td><b>独立的 {@code function_call} item</b></td></tr>
 *   <tr><td>工具结果</td><td>{@code role: "tool"} 消息</td>
 *       <td><b>独立的 {@code function_call_output} item</b></td></tr>
 *   <tr><td>输出上限</td><td>{@code max_tokens}</td><td><b>{@code max_output_tokens}</b></td></tr>
 *   <tr><td>结束原因</td><td>{@code finish_reason}</td><td><b>{@code status}</b></td></tr>
 *   <tr><td>流式</td><td>无名 SSE + {@code [DONE]}</td>
 *       <td><b>命名事件，且没有 {@code [DONE]}</b>（靠 {@code response.completed} 收尾）</td></tr>
 * </table>
 *
 * <p>之所以值得内置：Codex 一类工具链只认这个协议，而 DeepSeek 等厂商也已提供兼容端点，
 * 因此它属于"主流 API 格式"而不是某个厂商的私有方言。</p>
 *
 * <p>本类无状态、线程安全；{@code Codecs} 通过反射调用公开无参构造器创建实例。</p>
 */
public final class ResponsesApiCodec implements ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(ResponsesApiCodec.class);

    /** 补全端点路径。 */
    private static final String RESPONSES_PATH = "/responses";

    /** baseUrl 已带版本段（{@code /v1}、{@code /v4} …）时的识别规则。 */
    private static final Pattern VERSION_SUFFIX = Pattern.compile(".*/v\\d+", Pattern.CASE_INSENSITIVE);

    private static final String BEARER_PREFIX = "Bearer ";

    /** {@code tool_choice} 的关键字取值；其余取值一律视为具体工具名。 */
    private static final Set<String> TOOL_CHOICE_KEYWORDS = Set.of("auto", "none", "required");

    /** tool 结果缺失 call_id 时的占位值：保留字段结构，让上游报出更易定位的错误。 */
    private static final String UNKNOWN_CALL_ID = "call_unknown";

    /**
     * 本协议的能力声明。
     *
     * <p>与 Chat Completions 预设的差别只有 {@code maxContextTokens} 的取值口径 —— 同样取
     * 128k 作为通行窗口。{@code parallelToolCalls} 按"支持"声明：Responses 可以在一次响应里
     * 返回多个 {@code function_call} item，但它没有 Chat Completions 那样的
     * {@code parallel_tool_calls=false} 开关（部分兼容端点还会忽略该参数），
     * 是否并发执行由上层结合工具的 {@code parallelSafe()} 决定。</p>
     */
    public static final ModelCapabilities RECOMMENDED_CAPABILITIES = ModelCapabilities.builder()
            .streaming(true)
            .toolCalling(true)
            .parallelToolCalls(true)
            .vision(true)
            .thinking(true)
            .maxContextTokens(128_000)
            .build();

    /** {@code Codecs} 通过反射实例化，必须保留公开无参构造器。 */
    public ResponsesApiCodec() {
    }

    @Override
    public Protocol protocol() {
        return Protocol.RESPONSES;
    }

    @Override
    public ModelCapabilities capabilities() {
        return RECOMMENDED_CAPABILITIES;
    }

    /**
     * 拼出完整的 {@code POST} 地址，兼容三种 baseUrl 写法：
     *
     * <ul>
     *   <li>{@code https://api.openai.com/v1/responses} —— 已含端点，原样使用</li>
     *   <li>{@code https://api.openai.com/v1} —— 已含版本段，追加 {@code /responses}</li>
     *   <li>{@code https://api.openai.com} —— 只有域名，追加 {@code /v1/responses}</li>
     * </ul>
     *
     * <p>个别厂商把 Responses 挂在无版本段的路径上（例如 {@code https://host/responses}），
     * 这种情况把完整地址填进 {@code base-url} 即可，无需额外配置项。</p>
     */
    @Override
    public String endpoint(ModelConfig config, ChatRequest request) {
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new ModelException("模型 [" + (config == null ? "?" : config.name())
                    + "] 未配置 baseUrl，无法推导 Responses 地址");
        }
        String url = stripTrailingSlashes(config.baseUrl().trim());
        if (endsWithIgnoreCase(url, RESPONSES_PATH)) {
            return url;
        }
        if (VERSION_SUFFIX.matcher(url).matches()) {
            return url + RESPONSES_PATH;
        }
        return url + "/v1" + RESPONSES_PATH;
    }

    /** 认证头与 Chat Completions 一致（Bearer + 自定义头覆盖）。 */
    @Override
    public Map<String, String> headers(ModelConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config == null) {
            return headers;
        }
        if (config.hasApiKey()) {
            String apiKey = config.apiKey().trim();
            // 容忍用户把整段 "Bearer xxx" 填进 apiKey，避免拼出 Bearer Bearer
            headers.put("Authorization", startsWithIgnoreCase(apiKey, BEARER_PREFIX)
                    ? apiKey
                    : BEARER_PREFIX + apiKey);
        }
        config.headers().forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                headers.put(key, value);
            }
        });
        return headers;
    }

    // ------------------------------------------------------------------
    // 请求编码
    // ------------------------------------------------------------------

    @Override
    public String encode(ChatRequest request, ModelConfig config) {
        if (request == null) {
            throw new ModelException("编码 Responses 请求失败：ChatRequest 为空");
        }
        ObjectNode root = Json.object();
        root.put("model", config == null ? request.model() : config.resolveModel(request));

        writeInstructions(root, request);
        writeInput(root, request);
        writeTools(root, request);
        if (request.toolChoice() != null && !request.toolChoice().isBlank()) {
            writeToolChoice(root, request.toolChoice());
        }
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        // 字段名是 max_output_tokens：下发 max_tokens 会被判为未知参数
        if (request.maxTokens() != null) {
            root.put("max_output_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            root.put("top_p", request.topP());
        }
        if (request.stream()) {
            root.put("stream", true);
        }
        writeExtra(root, request);
        return Json.write(root);
    }

    /**
     * 系统提示走顶层 {@code instructions}，由所有 SYSTEM 消息拼接而来。
     *
     * <p>本心内部保留多条 SYSTEM 消息的粒度（便于不同来源各自追加），
     * 而 Responses 只认一个 {@code instructions} 字符串，因此在编码侧合并。</p>
     */
    private void writeInstructions(ObjectNode root, ChatRequest request) {
        String system = request.systemText();
        if (system != null && !system.isBlank()) {
            root.put("instructions", system);
        }
    }

    /**
     * 把统一消息模型摊平成 Responses 的 {@code input} item 列表。
     *
     * <p>摊平规则：</p>
     * <ul>
     *   <li>USER / ASSISTANT 的文本与图片 → 一条 {@code message} item；</li>
     *   <li>ASSISTANT 的每个 {@link ToolUsePart} → 一条独立的 {@code function_call} item；</li>
     *   <li>TOOL 消息的每个 {@link ToolResultPart} → 一条独立的 {@code function_call_output} item；</li>
     *   <li>SYSTEM 已提到顶层；{@link ThinkingPart} 不回灌（理由见 {@link #writeAssistantItems}）。</li>
     * </ul>
     */
    private void writeInput(ObjectNode root, ChatRequest request) {
        ArrayNode input = root.putArray("input");
        for (ChatMessage message : request.messages()) {
            switch (message.role()) {
                case SYSTEM -> {
                    // 已由 writeInstructions 处理
                }
                case USER -> input.add(writeMessageItem("user", message));
                case ASSISTANT -> writeAssistantItems(input, message);
                case TOOL -> writeToolOutputItems(input, message);
                default -> log.debug("跳过角色 {} 的消息（Responses 协议无对应表达）", message.role());
            }
        }
    }

    private void writeAssistantItems(ArrayNode input, ChatMessage message) {
        // 思维链不回灌：Responses 的 reasoning item 需要配套的加密内容 / 摘要结构，
        // 而本心的 ThinkingPart 只保存纯文本 —— 硬塞回去多数端点会直接 400。
        // 这与 Gemini 侧"思考内容仅解析、不回传"是同一个取舍。
        if (!textOnly(message).isEmpty()) {
            input.add(writeMessageItem("assistant", message));
        }
        for (ToolUsePart use : message.toolUses()) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call");
            item.put("call_id", resolveCallId(use.id()));
            item.put("name", use.name() == null ? "" : use.name());
            // arguments 是"JSON 文本"而非对象，原样透传，不做二次解析
            item.put("arguments", use.argumentsJson());
        }
    }

    private void writeToolOutputItems(ArrayNode input, ChatMessage message) {
        for (ContentPart part : message.parts()) {
            if (!(part instanceof ToolResultPart result)) {
                continue;
            }
            ObjectNode item = input.addObject();
            item.put("type", "function_call_output");
            item.put("call_id", resolveCallId(result.toolUseId()));
            // output 支持字符串或内容块数组；纯文本时用字符串最兼容。
            // 失败结果必须带上可辨识的标记：Responses 的 function_call_output 没有
            // is_error 之类的结构化字段，因此与 OpenAI Chat Completions 侧保持同一思路，
            // 加 [error] 前缀。少了它，模型看到"工具炸了"时无从判断这是工具正常返回的文本
            // 还是工具失败了，于是会把错误当成合法结果继续往下走，而不是改方案或重试。
            item.put("output", result.error() ? "[error] " + result.content() : result.content());
        }
    }

    /**
     * 一条 {@code message} item。
     *
     * <p>纯文本时 {@code content} 用字符串形态（最省 token 也最兼容），
     * 含图片时切换为内容块数组 —— 这是 Responses 表达多模态的唯一方式。</p>
     */
    private ObjectNode writeMessageItem(String role, ChatMessage message) {
        ObjectNode item = Json.object();
        item.put("type", "message");
        item.put("role", role);

        List<ImagePart> images = message.parts().stream()
                .filter(ImagePart.class::isInstance)
                .map(ImagePart.class::cast)
                .filter(image -> image.isInline() || (image.url() != null && !image.url().isBlank()))
                .toList();
        String text = textOnly(message);

        if (images.isEmpty()) {
            item.put("content", text);
            return item;
        }

        // 有图片：user 侧用 input_text、assistant 侧用 output_text。
        // 两种 part 类型协议都接受，但按语义分开更贴近原意，抓包时也能一眼看出是谁说的。
        String textPartType = "assistant".equals(role) ? "output_text" : "input_text";
        ArrayNode content = item.putArray("content");
        if (!text.isEmpty()) {
            ObjectNode textNode = content.addObject();
            textNode.put("type", textPartType);
            textNode.put("text", text);
        }
        for (ImagePart image : images) {
            ObjectNode node = content.addObject();
            node.put("type", "input_image");
            node.put("image_url", image.isInline() ? image.toDataUri() : image.url());
        }
        return item;
    }

    /**
     * 工具声明：Responses 的 {@code tools[]} 是<b>扁平</b>的 ——
     * {@code {type, name, description, parameters}}，没有 Chat Completions 那层 {@code function} 包装。
     * 这是两套协议最容易踩错的一处。
     */
    private void writeTools(ObjectNode root, ChatRequest request) {
        if (!request.hasTools()) {
            return;
        }
        ArrayNode tools = root.putArray("tools");
        for (ToolSpec spec : request.tools()) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            tool.put("name", spec.name());
            tool.put("description", spec.description() == null ? "" : spec.description());
            if (spec.inputSchema() != null && !spec.inputSchema().isNull()) {
                tool.set("parameters", spec.inputSchema());
            }
        }
    }

    /**
     * {@code tool_choice}：关键字直接下发字符串，具体工具名要包成
     * {@code {"type":"function","name":...}} —— 注意<b>不是</b> Chat Completions 的
     * {@code {"type":"function","function":{"name":...}}}。
     */
    private void writeToolChoice(ObjectNode root, String toolChoice) {
        String value = toolChoice.trim();
        if (TOOL_CHOICE_KEYWORDS.contains(value.toLowerCase(Locale.ROOT))) {
            root.put("tool_choice", value.toLowerCase(Locale.ROOT));
            return;
        }
        ObjectNode choice = root.putObject("tool_choice");
        choice.put("type", "function");
        choice.put("name", value);
    }

    /** {@code extra} 覆盖式写入顶层：厂商私有参数走这里透传。 */
    private void writeExtra(ObjectNode root, ChatRequest request) {
        request.extra().forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            root.set(key, Json.valueToTree(value));
        });
    }

    // ------------------------------------------------------------------
    // 响应解码
    // ------------------------------------------------------------------

    @Override
    public ChatResponse decode(String responseBody, ModelConfig config) {
        JsonNode root = Json.parseQuietly(responseBody);
        if (root == null || !root.isObject()) {
            throw new ModelException("Responses 响应不是合法 JSON 对象: " + Json.abbreviate(responseBody));
        }
        // 少数网关 200 也会返回 {"error": {...}}
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new ModelException("Responses 返回错误响应: " + errorText(error)
                    + " | body=" + Json.abbreviate(responseBody));
        }

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolUsePart> toolUses = new ArrayList<>();

        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                readOutputItem(item, text, reasoning, toolUses);
            }
        }
        if (text.isEmpty()) {
            // 部分兼容端点只给 output_text 这个便捷字段
            String convenience = textOrNull(root.get("output_text"));
            if (convenience != null) {
                text.append(convenience);
            }
        }
        if (text.isEmpty() && reasoning.isEmpty() && toolUses.isEmpty()) {
            throw new ModelException("Responses 响应既没有 output 也没有 output_text: "
                    + Json.abbreviate(responseBody));
        }

        // 内容块顺序与流式侧（StreamCollector）保持一致：思考 → 正文 → 工具调用
        List<ContentPart> parts = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            parts.add(new ThinkingPart(reasoning.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(toolUses);

        String status = textOrNull(root.get("status"));
        Usage usage = parseUsage(root.get("usage"));

        ChatResponse.Builder builder = ChatResponse.builder()
                .id(textOrNull(root.get("id")))
                .model(textOrNull(root.get("model")))
                .message(ChatMessage.assistant(parts))
                .finishReason(finishReasonOf(status, !toolUses.isEmpty()))
                .usage(usage);
        addRaw(builder, "id", root.get("id"));
        addRaw(builder, "model", root.get("model"));
        addRaw(builder, "object", root.get("object"));
        addRaw(builder, "created_at", root.get("created_at"));
        addRaw(builder, "status", root.get("status"));
        addRaw(builder, "usage", root.get("usage"));
        if (root.has("incomplete_details")) {
            addRaw(builder, "incomplete_details", root.get("incomplete_details"));
        }
        return builder.build();
    }

    /** 读取单个 output item，把内容并入三个累加器（顺序在 decode 末尾统一整理）。 */
    private void readOutputItem(JsonNode item, StringBuilder text, StringBuilder reasoning,
                                List<ToolUsePart> toolUses) {
        if (item == null || !item.isObject()) {
            return;
        }
        String type = textOrNull(item.get("type"));
        if (type == null) {
            return;
        }
        switch (type) {
            case "message" -> {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode block : content) {
                        if (isTextBlock(textOrNull(block.get("type")))) {
                            String value = textOrNull(block.get("text"));
                            if (value != null) {
                                text.append(value);
                            }
                        }
                    }
                }
            }
            case "function_call" -> toolUses.add(new ToolUsePart(
                    resolveCallId(textOrNull(item.get("call_id"))),
                    orEmpty(textOrNull(item.get("name"))),
                    orBraces(textOrNull(item.get("arguments")))));
            case "reasoning" -> reasoning.append(readReasoningText(item));
            default -> log.debug("跳过 Responses output item 类型 [{}]（本心暂无对应表达）", type);
        }
    }

    private boolean isTextBlock(String blockType) {
        return "output_text".equals(blockType) || "input_text".equals(blockType) || "text".equals(blockType);
    }

    /** reasoning item 的正文可能在 {@code content[].text}、{@code summary[].text} 或 {@code text}。 */
    private String readReasoningText(JsonNode item) {
        StringBuilder sb = new StringBuilder();
        appendTexts(sb, item.get("content"));
        appendTexts(sb, item.get("summary"));
        if (sb.isEmpty()) {
            String direct = textOrNull(item.get("text"));
            if (direct != null) {
                sb.append(direct);
            }
        }
        return sb.toString();
    }

    private void appendTexts(StringBuilder sb, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String value = textOrNull(node.get("text"));
            if (value != null) {
                sb.append(value);
            }
        }
    }

    /**
     * {@code status} → 统一结束原因。
     *
     * <p>Responses 没有 {@code finish_reason}，用的是 {@code status}
     * （{@code completed} / {@code incomplete} / {@code failed} / {@code in_progress}）。</p>
     *
     * <p>注意不能直接丢给 {@link FinishReason#fromWire}："completed" 不在那张表里
     * （表里认的是 {@code complete}），会归成 {@code UNKNOWN} —— 这里显式处理。</p>
     */
    private FinishReason finishReasonOf(String status, boolean hasToolCalls) {
        if ("completed".equals(status)) {
            // 有工具调用时归 TOOL_CALLS，否则上层不会去执行工具
            return hasToolCalls ? FinishReason.TOOL_CALLS : FinishReason.STOP;
        }
        if ("incomplete".equals(status)) {
            // 目前唯一的 incomplete 原因就是触达 max_output_tokens
            return FinishReason.LENGTH;
        }
        if ("failed".equals(status) || "cancelled".equals(status)) {
            return FinishReason.ERROR;
        }
        // status 缺失（部分兼容网关如此）时按有无工具调用兜底
        return hasToolCalls ? FinishReason.TOOL_CALLS : FinishReason.STOP;
    }

    private Usage parseUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Usage.ZERO;
        }
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int cached = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
        int reasoningTokens = usage.path("output_tokens_details").path("reasoning_tokens").asInt(0);
        return new Usage(input, output, cached, reasoningTokens);
    }

    @Override
    public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        return new ResponsesStreamDecoder(config, handler);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 只取正文：思维链与工具调用块不进 content。 */
    private String textOnly(ChatMessage message) {
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.parts()) {
            if (part instanceof TextPart textPart) {
                sb.append(textPart.text());
            }
        }
        return sb.toString();
    }

    private String resolveCallId(String id) {
        return id == null || id.isBlank() ? UNKNOWN_CALL_ID : id;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orBraces(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private String errorText(JsonNode error) {
        String message = textOrNull(error.get("message"));
        String type = textOrNull(error.get("type"));
        String code = textOrNull(error.get("code"));
        StringBuilder sb = new StringBuilder(message == null ? error.toString() : message);
        if (type != null) {
            sb.append(" [type=").append(type).append(']');
        }
        if (code != null) {
            sb.append(" [code=").append(code).append(']');
        }
        return sb.toString();
    }

    private void addRaw(ChatResponse.Builder builder, String key, JsonNode node) {
        if (node != null && !node.isNull()) {
            builder.raw(key, Json.mapper().convertValue(node, Object.class));
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        return value.length() >= suffix.length()
                && value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
