package com.benxin.llm.core.protocol.openai;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 协议编解码器。
 *
 * <p>之所以按"协议"而不是按"厂商"划分：DeepSeek、通义千问、Kimi、GLM、vLLM、
 * Ollama、OneAPI 等大量服务都兼容这套请求/响应结构，用户只改 baseUrl 即可切换厂商，
 * 无需新写任何 Codec。</p>
 *
 * <p>本类无状态、线程安全；{@code Codecs} 通过反射调用公开无参构造器创建实例。</p>
 *
 * <p>编解码过程中刻意"不认识的字段一律不下发"：只输出调用方显式设置的参数，
 * 避免给严格校验的兼容网关塞入 null / 空数组而触发 400。厂商私有参数一律走
 * {@link ChatRequest#extra()} 透传。</p>
 */
public final class OpenAiCodec implements ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCodec.class);

    /** 补全端点路径。 */
    private static final String COMPLETIONS_PATH = "/chat/completions";

    /** baseUrl 已带版本段（{@code /v1}、{@code /v4} …）时的识别规则。 */
    private static final Pattern VERSION_SUFFIX = Pattern.compile(".*/v\\d+", Pattern.CASE_INSENSITIVE);

    private static final String BEARER_PREFIX = "Bearer ";

    /** {@code tool_choice} 的关键字取值；其余取值一律视为具体工具名。 */
    private static final Set<String> TOOL_CHOICE_KEYWORDS = Set.of("auto", "none", "required");

    /** tool 结果缺失 tool_call_id 时的占位值：保留字段结构，让上游报出更易定位的错误。 */
    private static final String UNKNOWN_TOOL_CALL_ID = "call_unknown";

    /**
     * 公开无参构造器：{@code Codecs} 依赖 {@code getDeclaredConstructor().newInstance()} 实例化。
     * 显式声明（而非依赖隐式默认构造器）以免后续加入其它构造器时反射装配被悄悄破坏。
     */
    public OpenAiCodec() {
    }

    @Override
    public Protocol protocol() {
        return Protocol.OPENAI;
    }

    /**
     * 拼出完整的 {@code POST} 地址。
     *
     * <p>兼容三种 baseUrl 写法，用户既可以只填域名，也可以直接黏贴文档里的完整地址：</p>
     * <ul>
     *   <li>{@code https://api.openai.com/v1/chat/completions} —— 已含端点，原样使用</li>
     *   <li>{@code https://api.openai.com/v1} —— 已含版本段，追加 {@code /chat/completions}</li>
     *   <li>{@code https://api.deepseek.com} —— 只有域名，追加 {@code /v1/chat/completions}</li>
     * </ul>
     *
     * <p>与 Gemini 不同，OpenAI 协议的模型 id 放在请求体里而非 URL 上，
     * 因此 {@code request} 参数在此不参与拼接。</p>
     */
    @Override
    public String endpoint(ModelConfig config, ChatRequest request) {
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new ModelException("模型 [" + (config == null ? "?" : config.name())
                    + "] 未配置 baseUrl，无法推导 OpenAI 补全地址");
        }
        String url = stripTrailingSlashes(config.baseUrl().trim());
        if (endsWithIgnoreCase(url, COMPLETIONS_PATH)) {
            return url;
        }
        if (VERSION_SUFFIX.matcher(url).matches()) {
            return url + COMPLETIONS_PATH;
        }
        return url + "/v1" + COMPLETIONS_PATH;
    }

    /**
     * 认证头 + 自定义头。
     *
     * <p>自定义头放在最后叠加，因此可以覆盖 {@code Authorization}——Azure OpenAI、
     * 部分自建网关用的是 {@code api-key} 之类的头，需要这种覆盖能力。</p>
     */
    @Override
    public Map<String, String> headers(ModelConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config == null) {
            return headers;
        }
        if (config.hasApiKey()) {
            String apiKey = config.apiKey().trim();
            // 容忍用户把整段 "Bearer xxx" 填进 apiKey（配置文件里很常见），避免出现 Bearer Bearer
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

    @Override
    public String encode(ChatRequest request, ModelConfig config) {
        if (request == null) {
            throw new ModelException("编码 OpenAI 请求失败：ChatRequest 为空");
        }
        ObjectNode root = Json.object();
        root.put("model", config.resolveModel(request));
        writeMessages(root, request);
        writeTools(root, request);
        if (request.toolChoice() != null && !request.toolChoice().isBlank()) {
            writeToolChoice(root, request.toolChoice());
        }
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            root.put("top_p", request.topP());
        }
        // stop 为空列表时不下发：部分厂商要求 stop 至少 1 个元素，空数组会被判 400
        if (request.stop() != null && !request.stop().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            request.stop().forEach(stop::add);
        }
        if (request.stream()) {
            root.put("stream", true);
            // include_usage：让最后一帧带回 token 用量，否则流式调用拿不到计费数据
            root.putObject("stream_options").put("include_usage", true);
        }
        writeExtra(root, request);
        return Json.write(root);
    }

    @Override
    public ChatResponse decode(String responseBody, ModelConfig config) {
        JsonNode root = Json.parseQuietly(responseBody);
        if (root == null || !root.isObject()) {
            throw new ModelException("OpenAI 响应不是合法 JSON 对象: " + Json.abbreviate(responseBody));
        }
        // 少数网关 200 也会返回 {"error": {...}}，这类响应没有 choices，提前给出可读错误
        JsonNode error = root.get("error");
        if (error != null && !error.isNull() && !root.has("choices")) {
            throw new ModelException("OpenAI 返回错误响应: " + errorText(error)
                    + " | body=" + Json.abbreviate(responseBody));
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ModelException("OpenAI 响应缺少 choices: " + Json.abbreviate(responseBody));
        }
        JsonNode choice = choices.get(0);
        JsonNode messageNode = choice.path("message");
        if (!messageNode.isObject()) {
            throw new ModelException("OpenAI 响应缺少 choices[0].message: " + Json.abbreviate(responseBody));
        }

        String content = readContent(messageNode.get("content"));
        String reasoning = firstNonBlank(textOrNull(messageNode.get("reasoning_content")),
                textOrNull(messageNode.get("reasoning")));
        List<ToolUsePart> toolUses = readToolUses(messageNode);

        // 内容块顺序与流式侧（StreamCollector）保持一致：思考 → 正文 → 工具调用
        List<ContentPart> parts = new ArrayList<>();
        if (reasoning != null && !reasoning.isEmpty()) {
            parts.add(new ThinkingPart(reasoning));
        }
        if (!content.isEmpty()) {
            parts.add(new TextPart(content));
        }
        parts.addAll(toolUses);

        String wireReason = textOrNull(choice.get("finish_reason"));
        FinishReason finishReason = FinishReason.fromWire(wireReason);
        if (finishReason == FinishReason.UNKNOWN && !toolUses.isEmpty()) {
            // 部分兼容网关不回 finish_reason；有工具调用就必须归一到 TOOL_CALLS，否则上层不会去执行工具
            finishReason = FinishReason.TOOL_CALLS;
        }
        Usage usage = parseUsage(root.get("usage"));

        ChatResponse.Builder builder = ChatResponse.builder()
                .id(textOrNull(root.get("id")))
                .model(textOrNull(root.get("model")))
                .message(ChatMessage.assistant(parts))
                .finishReason(finishReason)
                .usage(usage);
        addRaw(builder, "id", root.get("id"));
        addRaw(builder, "model", root.get("model"));
        addRaw(builder, "object", root.get("object"));
        addRaw(builder, "created", root.get("created"));
        addRaw(builder, "system_fingerprint", root.get("system_fingerprint"));
        addRaw(builder, "usage", root.get("usage"));
        if (wireReason != null) {
            builder.raw("finish_reason", wireReason);
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            builder.raw("reasoning_content", reasoning);
        }
        return builder.build();
    }

    @Override
    public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        return new OpenAiStreamDecoder(config, handler);
    }

    // ------------------------------------------------------------------
    // 请求编码
    // ------------------------------------------------------------------

    private void writeMessages(ObjectNode root, ChatRequest request) {
        ArrayNode messages = root.putArray("messages");
        List<ChatMessage> source = request.messages();
        for (int i = 0; i < source.size(); i++) {
            ChatMessage message = source.get(i);
            switch (message.role()) {
                case SYSTEM -> messages.add(simpleMessage(Role.SYSTEM, message));
                case USER -> messages.add(writeUserMessage(message));
                case ASSISTANT -> messages.add(writeAssistantMessage(message, i, echoReasoning(request)));
                case TOOL -> appendToolMessages(messages, message);
                default -> log.debug("跳过角色 {} 的消息（OpenAI 协议无对应角色）", message.role());
            }
        }
    }

    private ObjectNode simpleMessage(Role role, ChatMessage message) {
        ObjectNode node = Json.object();
        node.put("role", role.wireName());
        node.put("content", textOnly(message));
        return node;
    }

    /**
     * USER 消息：纯文本时用字符串形态（最省 token 也最兼容），
     * 含图片时切换为内容块数组——这是 OpenAI 多模态的唯一表达方式。
     */
    private ObjectNode writeUserMessage(ChatMessage message) {
        ObjectNode node = Json.object();
        node.put("role", Role.USER.wireName());
        if (!hasImage(message)) {
            node.put("content", textOnly(message));
            return node;
        }
        ArrayNode content = Json.array();
        for (ContentPart part : message.parts()) {
            if (part instanceof TextPart text) {
                ObjectNode item = content.addObject();
                item.put("type", "text");
                item.put("text", text.text());
            } else if (part instanceof ImagePart image) {
                String url = resolveImageUrl(image);
                if (url == null) {
                    log.debug("跳过既无 base64 也无 url 的图片块");
                    continue;
                }
                ObjectNode item = content.addObject();
                item.put("type", "image_url");
                item.putObject("image_url").put("url", url);
            }
            // 其它内容块（思维链、工具块）在 user 消息里没有对应表达，直接忽略
        }
        if (content.isEmpty()) {
            // 图片全部无效时退化成空文本，保证 content 字段存在且结构合法
            node.remove("content");
            node.put("content", textOnly(message));
            return node;
        }
        node.set("content", content);
        return node;
    }

    /**
     * ASSISTANT 消息：工具调用必须原样回灌（{@code id} + {@code name} + {@code arguments} 字符串），
     * 否则紧随其后的 tool 结果消息会因为找不到对应的 tool_call_id 而被上游拒绝。
     */
    private ObjectNode writeAssistantMessage(ChatMessage message, int messageIndex, boolean echoReasoning) {
        ObjectNode node = Json.object();
        node.put("role", Role.ASSISTANT.wireName());
        String text = textOnly(message);
        // 只发工具调用时 content 必须为 null：空字符串会被部分厂商判为非法消息
        node.put("content", text.isEmpty() ? null : text);

        List<ToolUsePart> toolUses = message.toolUses();
        if (!toolUses.isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (int i = 0; i < toolUses.size(); i++) {
                ToolUsePart use = toolUses.get(i);
                ObjectNode call = calls.addObject();
                call.put("id", resolveToolCallId(use.id(), messageIndex, i));
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", use.name() == null ? "" : use.name());
                // arguments 是"JSON 文本"而非 JSON 对象，这里原样透传，不做二次解析
                function.put("arguments", use.argumentsJson());
            }
        }
        String thinking = message.thinking();
        if (echoReasoning && !thinking.isEmpty()) {
            // 思维链回灌默认关闭：deepseek-reasoner 明确禁止输入消息携带 reasoning_content（会 400），
            // 而多数兼容网关收到未知字段只是忽略、并无收益。需要时用
            // ChatRequest.extra("echo_reasoning", true) 或配置 extra 显式打开。
            node.put("reasoning_content", thinking);
        }
        return node;
    }

    /** 是否把思维链回灌给上游；默认关闭，见 {@link #writeAssistantMessage} 的说明。 */
    private static boolean echoReasoning(ChatRequest request) {
        Object flag = request.extra().get("echo_reasoning");
        return flag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(flag));
    }

    /**
     * TOOL 消息：OpenAI 用「一条消息一个 tool 结果」的扁平结构，
     * 因此本心一条含多个 ToolResultPart 的消息会被拆成多条。
     */
    private void appendToolMessages(ArrayNode messages, ChatMessage message) {
        List<ToolResultPart> results = new ArrayList<>();
        for (ContentPart part : message.parts()) {
            if (part instanceof ToolResultPart result) {
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            // 结构性兜底：没有 ToolResultPart 时至少把文本作为结果回灌，保持消息数量对齐
            ObjectNode node = Json.object();
            node.put("role", Role.TOOL.wireName());
            node.put("tool_call_id", orDefault(message.toolCallId(), UNKNOWN_TOOL_CALL_ID));
            node.put("content", textOnly(message));
            messages.add(node);
            return;
        }
        for (ToolResultPart result : results) {
            ObjectNode node = Json.object();
            node.put("role", Role.TOOL.wireName());
            node.put("tool_call_id", orDefault(result.toolUseId(), orDefault(message.toolCallId(),
                    UNKNOWN_TOOL_CALL_ID)));
            // 错误结果加前缀：模型对 [error] 前缀的语义识别度高于纯文本描述
            node.put("content", result.error() ? "[error] " + result.content() : result.content());
            messages.add(node);
        }
    }

    private void writeTools(ObjectNode root, ChatRequest request) {
        if (!request.hasTools()) {
            // 无工具时整个字段省略：个别厂商见到 tools: [] 会直接报错
            return;
        }
        ArrayNode tools = root.putArray("tools");
        for (ToolSpec spec : request.tools()) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", spec.name());
            function.put("description", spec.description());
            JsonNode schema = spec.inputSchema();
            // parameters 必须是对象型 JSON Schema；缺失或非法时用空对象 Schema 兜底
            function.set("parameters", schema != null && schema.isObject() ? schema : emptyObjectSchema());
        }
    }

    private void writeToolChoice(ObjectNode root, String toolChoice) {
        String value = toolChoice.trim();
        String keyword = value.toLowerCase(Locale.ROOT);
        if (TOOL_CHOICE_KEYWORDS.contains(keyword)) {
            root.put("tool_choice", keyword);
            return;
        }
        // 非关键字一律视为"强制调用某个具体工具"，转成 OpenAI 的具名 function 形态
        ObjectNode node = root.putObject("tool_choice");
        node.put("type", "function");
        node.putObject("function").put("name", value);
    }

    /**
     * 透传厂商私有参数（{@code reasoning_effort}、{@code max_completion_tokens}、
     * {@code parallel_tool_calls}、{@code thinking} 等）。
     *
     * <p>写在最后且为覆盖式：用户 extra 里的同名键优先级最高，这是"一切可替换"
     * 在协议层的最小让步——新特性不需要改本类。</p>
     */
    private void writeExtra(ObjectNode root, ChatRequest request) {
        request.extra().forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                return;
            }
            if (value == null) {
                root.putNull(key);
                return;
            }
            try {
                root.set(key, Json.valueToTree(value));
            } catch (IllegalArgumentException e) {
                throw new ModelException("请求 extra 字段 [" + key + "] 无法序列化为 JSON", e);
            }
        });
    }

    // ------------------------------------------------------------------
    // 响应解码（部分工具方法为包内共享，流式解码器复用同一套字段规则）
    // ------------------------------------------------------------------

    /** 把 {@code usage} 节点解析为统一用量；字段缺失一律记 0。 */
    static Usage parseUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Usage.ZERO;
        }
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        if (cached == 0) {
            // DeepSeek 等厂商把命中缓存的输入 token 记在 prompt_cache_hit_tokens
            cached = usage.path("prompt_cache_hit_tokens").asInt(0);
        }
        int reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
        return new Usage(input, output, cached, reasoning);
    }

    /**
     * 读取 {@code content}：标准形态是字符串，但部分兼容网关会回内容块数组
     * （例如 {@code [{"type":"text","text":"..."}]}），这里统一抽成纯文本。
     */
    static String readContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isTextual()) {
                    sb.append(item.asText());
                    continue;
                }
                JsonNode text = item.get("text");
                if (text != null && text.isTextual()) {
                    sb.append(text.asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** 取文本字段；缺失或 null 返回 null。对象/数组降级为 JSON 文本，避免丢信息。 */
    static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.isValueNode() ? node.asText() : Json.write(node);
    }

    private List<ToolUsePart> readToolUses(JsonNode messageNode) {
        JsonNode calls = messageNode.get("tool_calls");
        if (calls == null || !calls.isArray() || calls.isEmpty()) {
            return List.of();
        }
        List<ToolUsePart> result = new ArrayList<>(calls.size());
        int index = 0;
        for (JsonNode call : calls) {
            if (call == null || !call.isObject()) {
                index++;
                continue;
            }
            JsonNode function = call.get("function");
            String name = function == null ? "" : orDefault(textOrNull(function.get("name")), "");
            String arguments = readArguments(function == null ? null : function.get("arguments"));
            // 兜底 id 与流式侧保持同一种形态（call_<index>），避免同一场对话里两种风格混杂
            result.add(new ToolUsePart(orDefault(textOrNull(call.get("id")), "call_" + index), name, arguments));
            index++;
        }
        return result;
    }

    /** {@code arguments} 标准形态是 JSON 字符串；少数网关直接给对象，这里反序列化回字符串。 */
    private static String readArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return "{}";
        }
        if (arguments.isTextual()) {
            String raw = arguments.asText();
            return raw.isBlank() ? "{}" : raw;
        }
        return Json.write(arguments);
    }

    /**
     * 只拼正文（{@link TextPart}）。
     *
     * <p>不能直接用 {@link ChatMessage#text()}：它会把所有内容块的 {@code asText()} 都算进去，
     * 其中思维链块返回的是推理原文、工具块返回的是 {@code [name(args)]} 占位符。
     * 若直接用它当 {@code content}，思维链会被同时写进 {@code content} 和
     * {@code reasoning_content}，既浪费 token 又干扰模型；工具占位符更是纯粹的噪音。</p>
     */
    private static String textOnly(ChatMessage message) {
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.parts()) {
            if (part instanceof TextPart text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    private static void addRaw(ChatResponse.Builder builder, String key, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        builder.raw(key, Json.mapper().convertValue(node, Object.class));
    }

    private static String errorText(JsonNode error) {
        if (error.isObject()) {
            String message = textOrNull(error.get("message"));
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return Json.write(error);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 编码侧：工具调用 id 缺失时合成一个稳定 id，保证 assistant(tool_calls) 与 tool 消息能配对。 */
    private static String resolveToolCallId(String id, int messageIndex, int toolIndex) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return "call_" + messageIndex + "_" + toolIndex;
    }

    private static String resolveImageUrl(ImagePart image) {
        if (image.isInline()) {
            return image.toDataUri();
        }
        String url = image.url();
        return url == null || url.isBlank() ? null : url;
    }

    private static boolean hasImage(ChatMessage message) {
        for (ContentPart part : message.parts()) {
            if (part instanceof ImagePart) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static String stripTrailingSlashes(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        return value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
