package com.benxin.llm.core.protocol.anthropic;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Messages API（{@code POST /v1/messages}）编解码器。
 *
 * <p>Anthropic 的请求体与 OpenAI 差异很大，本类把这些差异全部收敛掉，上层只看到统一的
 * {@link ChatRequest} / {@link ChatResponse}：</p>
 *
 * <ul>
 *   <li>{@code system} 是<b>顶层字段</b>而不是一条 system 消息；</li>
 *   <li>{@code messages} 只允许 {@code user} / {@code assistant} 两种角色，且必须严格交替；</li>
 *   <li>工具结果必须作为 {@code user} 消息里的 {@code tool_result} 内容块回灌；</li>
 *   <li>工具声明用 {@code input_schema}（OpenAI 叫 {@code parameters}）；</li>
 *   <li>{@code max_tokens} 必填，没有服务端默认值。</li>
 * </ul>
 *
 * @see <a href="https://docs.anthropic.com/en/api/messages">Anthropic Messages API</a>
 */
public class AnthropicCodec implements ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCodec.class);

    /** Anthropic 要求显式声明版本；不带或写错会直接 400。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 未指定 maxTokens 时的兜底值：Anthropic 的 max_tokens 是必填字段。 */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** {@code Codecs} 通过反射实例化，必须保留公开无参构造器。 */
    public AnthropicCodec() {
    }

    @Override
    public Protocol protocol() {
        return Protocol.ANTHROPIC;
    }

    // ------------------------------------------------------------------ 端点

    @Override
    public String endpoint(ModelConfig config, ChatRequest request) {
        String base = config == null ? null : config.baseUrl();
        if (isBlank(base)) {
            base = DEFAULT_BASE_URL;
        }
        String url = stripTrailingSlash(base);
        // 用户可能写成 https://host、https://host/v1、https://host/v1/messages 三种形态
        // （反代 / 兼容网关经常把完整路径直接配进 baseUrl），这里按后缀去重，
        // 避免拼出 /v1/v1/messages 这种 404 路径。
        if (endsWithIgnoreCase(url, "/messages")) {
            return url;
        }
        if (endsWithIgnoreCase(url, "/v1")) {
            return url + "/messages";
        }
        return url + "/v1/messages";
    }

    @Override
    public Map<String, String> headers(ModelConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        if (config != null && config.hasApiKey()) {
            headers.put("x-api-key", config.apiKey());
        }
        // 用户配置放最后：改成 Bedrock / Vertex 代理时可能需要换掉认证头、补 anthropic-beta 等，
        // 必须允许覆盖内置值。
        if (config != null) {
            headers.putAll(config.headers());
        }
        return headers;
    }

    // ------------------------------------------------------------------ 编码

    @Override
    public String encode(ChatRequest request, ModelConfig config) {
        ObjectNode body = Json.object();

        String model = config.resolveModel(request);
        if (!isBlank(model)) {
            body.put("model", model);
        }

        // system 在 Anthropic 里是顶层字段而非消息；多条 SYSTEM 消息用空行拼接成一个 system，
        // 语义上与 OpenAI 的多条 system 消息等价（且 Anthropic 只接受一个 system）。
        String system = request.systemText();
        if (!isBlank(system)) {
            body.put("system", system);
        }

        // Anthropic 没有 max_tokens 的服务端默认值，缺失会直接 400：请求 > 模型配置 > 框架兜底。
        Integer maxTokens = request.maxTokens() != null
                ? request.maxTokens()
                : (config.maxTokens() != null ? config.maxTokens() : DEFAULT_MAX_TOKENS);
        body.put("max_tokens", maxTokens);

        body.set("messages", encodeMessages(request.conversation()));

        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
        }
        if (request.stop() != null && !request.stop().isEmpty()) {
            ArrayNode stopSequences = body.putArray("stop_sequences");
            request.stop().forEach(stopSequences::add);
        }
        if (request.stream()) {
            body.put("stream", true);
        }

        encodeTools(request, body);

        // 协议特有字段（top_k、thinking、metadata …）最后覆盖式写入，
        // 保证"用户在 extra 里写的东西拥有最终决定权"。
        // 注意：这里刻意不合并 config.extra()——HttpLlmModel#merge 已经把配置级 extra
        // 折叠进 request.extra()，此处再合一次会导致配置值反过来覆盖请求值。
        request.extra().forEach((key, value) -> {
            if (key == null) {
                return;
            }
            JsonNode node = Json.valueToTree(value);
            if (node == null) {
                body.putNull(key);
            } else {
                body.set(key, node);
            }
        });

        return Json.write(body);
    }

    private void encodeTools(ChatRequest request, ObjectNode body) {
        String choice = request.toolChoice() == null ? "" : request.toolChoice().trim();

        // Anthropic 没有 OpenAI 的 tool_choice=none，禁用工具的唯一表达方式就是不下发 tools。
        if ("none".equalsIgnoreCase(choice)) {
            return;
        }

        if (request.hasTools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("name", spec.name());
                tool.put("description", spec.description() == null ? "" : spec.description());
                // 字段名是 input_schema，不是 OpenAI 的 parameters；写错会被 400 拒掉。
                tool.set("input_schema", normalizeSchema(spec.inputSchema()));
            }
        }

        if (choice.isEmpty()) {
            // null / 空 → 交给 Anthropic 默认的 auto
            return;
        }
        ObjectNode toolChoice = Json.object();
        switch (choice.toLowerCase(Locale.ROOT)) {
            case "auto" -> toolChoice.put("type", "auto");
            // Anthropic 用 any 表示"必须调用工具"（OpenAI 叫 required）
            case "required", "any" -> toolChoice.put("type", "any");
            default -> {
                // 其余取值一律当作具体工具名（工具名大小写敏感，用原始值）
                toolChoice.put("type", "tool");
                toolChoice.put("name", choice);
            }
        }
        body.set("tool_choice", toolChoice);
    }

    /** Anthropic 要求 input_schema 是一个 object schema，缺失时补一个空对象 schema。 */
    private static JsonNode normalizeSchema(JsonNode schema) {
        if (schema != null && schema.isObject()) {
            return schema;
        }
        if (schema != null && !schema.isNull()) {
            log.debug("工具 input_schema 不是 JSON 对象，已退化为空对象 schema: {}", Json.abbreviate(schema.toString()));
        }
        ObjectNode fallback = Json.object();
        fallback.put("type", "object");
        fallback.putObject("properties");
        return fallback;
    }

    /**
     * 把统一消息列表翻译成 Anthropic 的 {@code messages} 数组。
     *
     * <p><b>为什么不能"一条内部消息 = 一条 Anthropic 消息"</b>：Anthropic 强制要求
     * user / assistant 严格交替，并且 {@code tool_result} 必须放在"携带对应 {@code tool_use}
     * 的那条 assistant 消息"之后的第一条 user 消息里。而本心内部的一次并行工具调用会拆成
     * 多条 TOOL 消息（一个工具一条），直译就会产生连续多条 user 消息而被打回。
     * 因此这里按角色做<b>顺序合并</b>：连续的同角色内容块落进同一条消息的 content 数组，
     * 连续的 TOOL 消息自然合并成同一个 user turn 里的多个 {@code tool_result} block——
     * 这正是 Anthropic 唯一接受的回灌形态。</p>
     */
    private ArrayNode encodeMessages(List<ChatMessage> conversation) {
        ArrayNode messages = Json.array();
        String currentRole = null;
        ArrayNode currentContent = null;
        // 一次 encode 共用一个上下文：tool_result 可能出现在后面的消息里，
        // 需要回查前面 assistant 消息下发过的 tool_use id
        EncodeContext context = new EncodeContext();

        for (ChatMessage message : conversation) {
            for (EncodeContext.PendingBlock pending : encodeBlocks(message, context)) {
                if (currentContent == null || !pending.role().equals(currentRole)) {
                    ObjectNode node = messages.addObject();
                    node.put("role", pending.role());
                    currentContent = node.putArray("content");
                    currentRole = pending.role();
                }
                currentContent.add(pending.block());
            }
        }
        return messages;
    }

    /** 把一条统一消息拆成"角色 + 内容块"序列；角色可能与消息本身不同（tool_result 强制进 user）。 */
    private List<EncodeContext.PendingBlock> encodeBlocks(ChatMessage message, EncodeContext context) {
        List<EncodeContext.PendingBlock> blocks = new ArrayList<>();
        boolean assistant = message.role() == Role.ASSISTANT;
        String messageRole = assistant ? ROLE_ASSISTANT : ROLE_USER;
        boolean hasToolResult = false;

        for (ContentPart part : message.parts()) {
            if (part instanceof TextPart text) {
                String value = text.text();
                if (value.isEmpty()) {
                    // 空 text block 会被 Anthropic 判为非法内容块，直接丢弃
                    continue;
                }
                ObjectNode block = Json.object();
                block.put("type", "text");
                block.put("text", value);
                blocks.add(new EncodeContext.PendingBlock(messageRole, block));
            } else if (part instanceof ImagePart image) {
                ObjectNode block = encodeImage(image);
                if (block != null) {
                    blocks.add(new EncodeContext.PendingBlock(messageRole, block));
                }
            } else if (part instanceof ThinkingPart thinking) {
                if (!assistant) {
                    // thinking 只允许出现在 assistant 消息里
                    log.debug("ThinkingPart 只允许位于 assistant 消息，已跳过: {}", message);
                    continue;
                }
                if (isBlank(thinking.text()) && isBlank(thinking.signature())) {
                    continue;
                }
                ObjectNode block = Json.object();
                block.put("type", "thinking");
                block.put("thinking", thinking.text() == null ? "" : thinking.text());
                if (!isBlank(thinking.signature())) {
                    // 多轮对话里 Anthropic 会校验 thinking 的 signature，有就必须原样带回
                    block.put("signature", thinking.signature());
                }
                blocks.add(new EncodeContext.PendingBlock(ROLE_ASSISTANT, block));
            } else if (part instanceof ToolUsePart toolUse) {
                if (!assistant) {
                    log.debug("ToolUsePart 只允许位于 assistant 消息，已跳过: {}", message);
                    continue;
                }
                blocks.add(new EncodeContext.PendingBlock(ROLE_ASSISTANT, encodeToolUse(toolUse, context)));
            } else if (part instanceof ToolResultPart result) {
                hasToolResult = true;
                // 工具结果永远属于 user turn，与承载它的内部消息角色无关
                blocks.add(new EncodeContext.PendingBlock(ROLE_USER, encodeToolResult(result, context)));
            }
        }

        // 兜底：手工用 builder 构造的 TOOL 消息可能只填了 message 级 toolCallId 而没有
        // ToolResultPart；此时仍然补一个 tool_result，否则这轮工具调用在 Anthropic 眼里
        // 就是"没有回灌"，下一轮必然 400。
        if (!hasToolResult && message.role() == Role.TOOL && !isBlank(message.toolCallId())) {
            ToolResultPart result = new ToolResultPart(message.toolCallId(), message.name(), message.text(), false);
            blocks.add(new EncodeContext.PendingBlock(ROLE_USER, encodeToolResult(result, context)));
        }
        return blocks;
    }

    private static ObjectNode encodeImage(ImagePart image) {
        ObjectNode source = Json.object();
        if (image.isInline()) {
            source.put("type", "base64");
            source.put("media_type", isBlank(image.mediaType()) ? "image/png" : image.mediaType());
            source.put("data", image.base64Data());
        } else if (!isBlank(image.url())) {
            // Anthropic 的外链图片源：{"type":"url","url":"..."}
            source.put("type", "url");
            source.put("url", image.url());
        } else {
            log.debug("ImagePart 既没有 base64 数据也没有 URL，已跳过");
            return null;
        }
        ObjectNode block = Json.object();
        block.put("type", "image");
        block.set("source", source);
        return block;
    }

    private static ObjectNode encodeToolUse(ToolUsePart part, EncodeContext context) {
        String id = part.id();
        if (isBlank(id)) {
            // Anthropic 的 tool_use.id 必填，且后续 tool_result 要靠它关联，不能为空
            id = "toolu_" + UUID.randomUUID().toString().replace("-", "");
            log.debug("tool_use 缺少 id，已合成 {} 用于 [{}]", id, part.name());
        }
        context.rememberToolUseId(part.name(), id);

        ObjectNode block = Json.object();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", part.name() == null ? "" : part.name());
        JsonNode input = Json.parseQuietly(part.argumentsJson());
        if (input == null || !input.isObject()) {
            // input 必须是对象；参数串损坏时退化成空对象，让整条请求仍然可用
            if (input != null) {
                log.debug("tool_use 参数不是 JSON 对象，已按空对象下发: {}", Json.abbreviate(part.argumentsJson()));
            }
            input = Json.object();
        }
        block.set("input", input);
        return block;
    }

    private static ObjectNode encodeToolResult(ToolResultPart part, EncodeContext context) {
        String toolUseId = context.resolveToolUseId(part.toolUseId(), part.name());
        if (isBlank(toolUseId)) {
            log.warn("tool_result 缺少 tool_use_id，且无法按工具名 [{}] 关联到历史 tool_use，Anthropic 会拒绝该请求",
                    part.name());
        }
        ObjectNode block = Json.object();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId == null ? "" : toolUseId);
        block.put("content", part.content() == null ? "" : part.content());
        if (part.error()) {
            block.put("is_error", true);
        }
        return block;
    }

    // ------------------------------------------------------------------ 解码

    @Override
    public ChatResponse decode(String responseBody, ModelConfig config) {
        JsonNode root = Json.parseQuietly(responseBody);
        if (root == null || !root.isObject()) {
            throw new ModelException("Anthropic 响应不是合法的 JSON 对象: " + Json.abbreviate(responseBody));
        }
        JsonNode error = root.path("error");
        if (error.isObject()) {
            // 个别网关即使出错也返回 HTTP 200 + error 信封，这里一并识别
            throw new ModelException("Anthropic 返回错误 [" + error.path("type").asText("unknown") + "]: "
                    + error.path("message").asText("") + " | " + Json.abbreviate(responseBody));
        }
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            throw new ModelException("Anthropic 响应缺少 content 数组: " + Json.abbreviate(responseBody));
        }

        List<ContentPart> parts = new ArrayList<>();
        ArrayNode redacted = Json.array();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> parts.add(new TextPart(block.path("text").asText("")));
                case "thinking" -> parts.add(new ThinkingPart(block.path("thinking").asText(""),
                        text(block, "signature")));
                case "tool_use" -> parts.add(new ToolUsePart(text(block, "id"),
                        block.path("name").asText(""), toArgumentsJson(block.path("input"))));
                case "redacted_thinking" -> {
                    // 加密的思维链无法还原成文本，本心内部没有对应表示：忽略内容块，
                    // 但把它留在 raw 里，避免排障时"响应里明明有东西却看不到"。
                    redacted.add(block);
                    log.debug("忽略 redacted_thinking 内容块（Anthropic 未公开其明文）");
                }
                default -> log.debug("忽略未知内容块类型: {}", type);
            }
        }

        JsonNode usageNode = root.path("usage");
        Usage usage = new Usage(
                usageNode.path("input_tokens").asInt(0),
                usageNode.path("output_tokens").asInt(0),
                // Anthropic 的 prompt cache 命中量；cache_creation 属于"写入缓存"，不计入命中口径
                usageNode.path("cache_read_input_tokens").asInt(0),
                0);

        Map<String, Object> raw = new LinkedHashMap<>(Json.toMap(root));
        if (!redacted.isEmpty()) {
            raw.put("redacted_thinking", Json.mapper().convertValue(redacted, List.class));
        }

        return ChatResponse.builder()
                .id(text(root, "id"))
                .model(text(root, "model"))
                .message(ChatMessage.assistant(parts))
                .finishReason(FinishReason.fromWire(root.path("stop_reason").asText(null)))
                .usage(usage)
                .raw(raw)
                .build();
    }

    /** {@code input} 反向序列化成 argumentsJson；非对象一律退化为空对象。 */
    private static String toArgumentsJson(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "{}";
        }
        if (input.isObject()) {
            return Json.write(input);
        }
        if (input.isTextual()) {
            // 少数网关把 input 二次编码成字符串，这里解开一层
            JsonNode unwrapped = Json.parseQuietly(input.asText());
            if (unwrapped != null && unwrapped.isObject()) {
                return Json.write(unwrapped);
            }
        }
        log.debug("tool_use.input 不是 JSON 对象，argumentsJson 退化为空对象: {}", Json.abbreviate(input.toString()));
        return "{}";
    }

    // ------------------------------------------------------------------ 流式

    @Override
    public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        return new AnthropicStreamDecoder(config, handler);
    }

    // ------------------------------------------------------------------ 工具方法

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String stripTrailingSlash(String url) {
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        int offset = value.length() - suffix.length();
        return offset >= 0 && value.regionMatches(true, offset, suffix, 0, suffix.length());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 一次 encode 调用的临时状态。
     *
     * <p>刻意做成方法内局部对象而不是 Codec 字段：编解码器必须无状态，
     * 否则同一个实例被多线程/多请求复用时工具 id 会串。</p>
     */
    private static final class EncodeContext {

        /** 工具名 → 尚未配对的 tool_use id 队列（按下发顺序）。 */
        private final Map<String, Deque<String>> pendingToolUseIds = new HashMap<>();

        void rememberToolUseId(String name, String id) {
            pendingToolUseIds.computeIfAbsent(name == null ? "" : name, key -> new ArrayDeque<>()).addLast(id);
        }

        /**
         * 用工具名兜底反查 tool_use id。
         *
         * <p>本心内部正常路径一定带 id；但若调用方构造 {@link ToolResultPart} 时漏了 id，
         * 用"同名工具最近一次尚未配对的下发"来补，比直接下发空 id 让 Anthropic 报 400 更有用。</p>
         */
        String resolveToolUseId(String toolUseId, String name) {
            if (!isBlank(toolUseId)) {
                return toolUseId;
            }
            Deque<String> queue = pendingToolUseIds.get(name == null ? "" : name);
            return queue == null || queue.isEmpty() ? null : queue.pollFirst();
        }

        /** 已确定归属角色的内容块。 */
        private record PendingBlock(String role, ObjectNode block) {
        }
    }
}
