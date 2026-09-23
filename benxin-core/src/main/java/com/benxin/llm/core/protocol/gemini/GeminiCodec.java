package com.benxin.llm.core.protocol.gemini;

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

/**
 * Google Gemini（{@code generativelanguage.googleapis.com}）协议编解码器。
 *
 * <p>Gemini 是与 OpenAI 差异最大的协议，本类把差异全部吸收，向上层暴露统一的
 * {@link ChatRequest} / {@link ChatResponse}：</p>
 *
 * <table border="1">
 *   <caption>字段映射</caption>
 *   <tr><th>本心</th><th>Gemini</th></tr>
 *   <tr><td>System 消息</td><td>顶层 {@code systemInstruction.parts[].text}</td></tr>
 *   <tr><td>role=ASSISTANT</td><td>{@code contents[].role = "model"}</td></tr>
 *   <tr><td>role=TOOL</td><td>{@code contents[].role = "user"}（functionResponse 由 user 侧发出）</td></tr>
 *   <tr><td>TextPart / ImagePart</td><td>{@code parts[].text} / {@code parts[].inlineData}</td></tr>
 *   <tr><td>ToolUsePart / ToolResultPart</td><td>{@code parts[].functionCall} / {@code parts[].functionResponse}</td></tr>
 *   <tr><td>ToolSpec</td><td>{@code tools[].functionDeclarations[]}</td></tr>
 *   <tr><td>toolChoice</td><td>{@code toolConfig.functionCallingConfig}</td></tr>
 *   <tr><td>temperature / maxTokens / topP / stop</td>
 *       <td>{@code generationConfig.temperature / maxOutputTokens / topP / stopSequences}</td></tr>
 * </table>
 *
 * <p>三处必须知道的取舍（代码中对应位置也有注释）：</p>
 * <ol>
 *   <li><b>ThinkingPart 编码时直接丢弃</b>：Gemini 的 thought 是响应侧概念，
 *       把思维链当输入回灌会被判非法请求。</li>
 *   <li><b>tool id 由本心合成</b>：Gemini 的 {@code functionCall} 没有 id 字段，
 *       而 {@link ToolUsePart#id()} 非空语义更强，因此按出现顺序合成 {@code call_<n>}；
 *       回灌时真正起作用的对应关系是工具名（{@code functionResponse.name}）。</li>
 *   <li><b>JSON Schema 必须清洗</b>：Gemini 只接受 OpenAPI 子集，
 *       {@code additionalProperties} / {@code default} / {@code title} 等关键字会导致 400。</li>
 * </ol>
 *
 * <p>具备公开无参构造器，供 {@code Codecs} 反射实例化。</p>
 */
public class GeminiCodec implements ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(GeminiCodec.class);

    /** 未配置 baseUrl 时的官方端点。 */
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /** Gemini 当前稳定版本前缀。 */
    private static final String API_VERSION = "v1beta";

    /** baseUrl 里已经写了版本号的情况，避免拼出 {@code /v1beta/v1beta/...}。 */
    private static final Set<String> VERSION_SEGMENTS = Set.of("v1", "v1beta", "v1alpha");

    private static final String PATH_GENERATE = ":generateContent";
    private static final String PATH_STREAM = ":streamGenerateContent";

    /** 解码阶段合成 id 的前缀；不含工具名，因此不能反推出工具名。 */
    private static final String SYNTHETIC_CALL_PREFIX = "call_";

    /**
     * Gemini 的 {@code Schema} 只认 OpenAPI 3.0 的一个子集，下面这些关键字会被拒（400），
     * 必须在递归清洗时剔除（本心的 {@code JsonSchemaGenerator} 恰好就会产出
     * {@code additionalProperties} 与 {@code default}）。
     */
    private static final Set<String> UNSUPPORTED_SCHEMA_KEYS = Set.of(
            "additionalProperties", "additionalItems", "unevaluatedProperties", "unevaluatedItems",
            "patternProperties", "dependentSchemas", "dependentRequired", "dependencies",
            "$schema", "$id", "$anchor", "$ref", "$dynamicRef", "$comment", "$defs", "definitions",
            "default", "title", "examples", "readOnly", "writeOnly", "deprecated");

    /** "字段名 → 子 schema" 的映射容器：其键是用户自定义的参数名，不能当关键字剔除。 */
    private static final Set<String> SCHEMA_MAP_KEYS = Set.of("properties");

    // ------------------------------------------------------------------ 基础

    @Override
    public Protocol protocol() {
        return Protocol.GEMINI;
    }

    // ------------------------------------------------------------------ endpoint

    /**
     * {@code {base}/v1beta/models/{model}:generateContent}
     * 或流式的 {@code {base}/v1beta/models/{model}:streamGenerateContent?alt=sse}。
     */
    @Override
    public String endpoint(ModelConfig config, ChatRequest request) {
        String base = normalizeBaseUrl(config == null ? null : config.baseUrl());
        String model = stripModelsPrefix(config == null ? null : config.resolveModel(request));
        boolean stream = request != null && request.stream();
        return base + "/models/" + model + (stream ? PATH_STREAM + "?alt=sse" : PATH_GENERATE);
    }

    /** 规整 baseUrl：补默认值、去尾部斜杠、识别已带版本号或已带 {@code /models} 的写法。 */
    private static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 有的使用者会把 /models 也写进 baseUrl，这里剥掉，避免拼出 /models/models/...
        if (url.toLowerCase(Locale.ROOT).endsWith("/models")) {
            url = url.substring(0, url.length() - "/models".length());
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String version : VERSION_SEGMENTS) {
            if (lower.endsWith("/" + version)) {
                // 已经是 .../v1beta（或 .../v1），不能再叠一层
                return url;
            }
        }
        return url + "/" + API_VERSION;
    }

    /** 允许把模型配成 {@code models/gemini-2.0-flash} 这种全名。 */
    private static String stripModelsPrefix(String model) {
        String value = model == null ? "" : model.trim();
        return value.startsWith("models/") ? value.substring("models/".length()) : value;
    }

    // ------------------------------------------------------------------ headers

    /**
     * 密钥优先走 {@code x-goog-api-key} 请求头，而不是 {@code ?key=} 查询串：
     * URL 会进网关访问日志与代理缓存，请求头相对安全，也是 Google 官方推荐方式。
     * apiKey 为空时不下发该头，方便走自建代理（由代理自己注入密钥）。
     */
    @Override
    public Map<String, String> headers(ModelConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config != null && config.hasApiKey()) {
            headers.put("x-goog-api-key", config.apiKey());
        }
        if (config != null) {
            // 配置里的自定义头最后叠加，允许覆盖上面的默认值
            headers.putAll(config.headers());
        }
        return headers;
    }

    // ------------------------------------------------------------------ encode

    @Override
    public String encode(ChatRequest request, ModelConfig config) {
        ChatRequest req = request == null ? ChatRequest.builder().build() : request;
        ObjectNode root = Json.object();
        writeSystemInstruction(root, req);
        writeContents(root, req);
        writeTools(root, req);
        writeToolConfig(root, req);
        writeGenerationConfig(root, req);
        // extra 最后写入，覆盖式生效
        applyExtra(root, req);
        return Json.write(root);
    }

    /** system 消息统一提升到顶层 {@code systemInstruction}。 */
    private static void writeSystemInstruction(ObjectNode root, ChatRequest request) {
        String system = request.systemText();
        if (system == null || system.isBlank()) {
            return;
        }
        // systemInstruction 是精简版 Content：只有 parts，没有 role
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
    }

    /**
     * 写 {@code contents}：把相邻同角色消息合并进同一个 content。
     *
     * <p>Gemini 明确拒绝连续两条同角色 content，而本心的一条消息只表达一种角色，
     * 历史里连续两条 user（或 assistant 紧跟 tool 结果）都很常见，因此合并是必须的。</p>
     */
    private static void writeContents(ObjectNode root, ChatRequest request) {
        ArrayNode contents = root.putArray("contents");
        String currentRole = null;
        ArrayNode currentParts = null;
        int toolCallSeq = 0;
        for (ChatMessage message : request.messages()) {
            if (message == null || message.role() == Role.SYSTEM) {
                continue;
            }
            ArrayNode parts = Json.array();
            toolCallSeq = appendParts(parts, message, toolCallSeq);
            if (parts.isEmpty()) {
                // 空消息（例如只有被丢弃的 ThinkingPart）不下发，也不打断角色合并
                continue;
            }
            String role = wireRole(message.role());
            if (currentParts != null && role.equals(currentRole)) {
                parts.forEach(currentParts::add);
            } else {
                ObjectNode content = contents.addObject();
                content.put("role", role);
                currentParts = content.putArray("parts");
                parts.forEach(currentParts::add);
                currentRole = role;
            }
        }
    }

    /** Gemini 的角色命名：assistant → model，其余（含 TOOL）→ user。 */
    private static String wireRole(Role role) {
        // TOOL 必须映射成 user：Gemini 的 functionResponse 是从"用户侧"回灌给模型的
        return role == Role.ASSISTANT ? "model" : "user";
    }

    /** 把一条本心消息的内容块翻译成 Gemini parts，返回递增后的工具调用序号。 */
    private static int appendParts(ArrayNode parts, ChatMessage message, int toolCallSeq) {
        for (ContentPart part : message.parts()) {
            if (part == null) {
                continue;
            }
            if (part instanceof TextPart text) {
                appendText(parts, text.text());
            } else if (part instanceof ImagePart image) {
                appendImage(parts, image);
            } else if (part instanceof ToolUsePart toolUse) {
                toolCallSeq++;
                appendToolUse(parts, toolUse, toolCallSeq);
            } else if (part instanceof ToolResultPart toolResult) {
                appendToolResult(parts, toolResult);
            } else if (part instanceof ThinkingPart) {
                // 取舍：Gemini 的思考是"响应侧"概念（part.thought=true + thoughtSignature），
                // 把思维链原样回灌不属于合法输入；而且本心的 ThinkingPart 没有保存 Gemini 的
                // thoughtSignature，即使回灌也过不了校验。因此这里直接丢弃。
                // 影响可控：Gemini 本身不要求（也不接受）把上一轮的思考带回下一轮。
                log.debug("Gemini 编码：丢弃 ThinkingPart（Gemini 不接受把 thought 作为输入回灌）");
            } else {
                log.debug("Gemini 编码：忽略暂不支持的内容块类型 {}", part.type());
            }
        }
        return toolCallSeq;
    }

    private static void appendText(ArrayNode parts, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        parts.addObject().put("text", text);
    }

    private static void appendImage(ArrayNode parts, ImagePart image) {
        if (image.isInline()) {
            ObjectNode inlineData = parts.addObject().putObject("inlineData");
            inlineData.put("mimeType", image.mediaType() == null || image.mediaType().isBlank()
                    ? "image/png" : image.mediaType());
            inlineData.put("data", image.base64Data());
            return;
        }
        String url = image.url();
        if (url != null && !url.isBlank()) {
            // Gemini 不吃外链图片：inlineData 只收 base64，fileData 只认 Google File API 的 URI，
            // 因此普通 http(s) 图片无法直接下发，这里降级成一句文字说明（至少让模型知道有这么张图），
            // 需要真正理解图片时请由调用方先下载并转成 base64 的 ImagePart。
            log.debug("Gemini 编码：外链图片无法直接下发，降级为文本说明 {}", url);
            appendText(parts, "[图片: " + url + "]");
        }
    }

    private static void appendToolUse(ArrayNode parts, ToolUsePart toolUse, int sequence) {
        ObjectNode functionCall = parts.addObject().putObject("functionCall");
        functionCall.put("name", toolUse.name() == null ? "" : toolUse.name());
        functionCall.set("args", parseArguments(toolUse.argumentsJson()));
        // Gemini 的 functionCall 没有 id 字段，本心的 ToolUsePart.id 在请求体里没有落点。
        // 这里仍按 "名字+序号" 合成一个稳定 id 仅用于日志追踪；它同时也是名字缺失时的
        // 还原线索（见 toolNameOf）。回灌时真正起作用的是 name。
        if (log.isDebugEnabled()) {
            log.debug("Gemini 编码：functionCall {} 使用合成 id {}", functionCall.get("name").asText(),
                    synthesizeToolCallId(toolUse.name(), sequence));
        }
    }

    private static void appendToolResult(ArrayNode parts, ToolResultPart toolResult) {
        ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
        // Gemini 用 name（而不是 id）把 functionResponse 对回 functionCall；
        // 本心 ToolResultPart 里保存了工具名，所以这条链是通的。
        functionResponse.put("name", toolNameOf(toolResult));
        // 注意：不回传 functionResponse.id —— Gemini 要求它与 functionCall.id 对应，
        // 而 functionCall 侧的 id 是本心合成的、从未下发给上游，回传反而会校验失败。
        ObjectNode response = functionResponse.putObject("response");
        if (toolResult.error()) {
            response.put("error", toolResult.content());
        } else {
            response.put("result", toolResult.content());
        }
    }

    /** {@code functionCall.args} 必须是 JSON 对象（protobuf Struct），这里做兜底转换。 */
    private static JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Json.object();
        }
        JsonNode parsed = Json.parseQuietly(argumentsJson);
        if (parsed == null || parsed.isNull()) {
            log.debug("Gemini 编码：工具参数不是合法 JSON，按空对象下发: {}", Json.abbreviate(argumentsJson));
            return Json.object();
        }
        if (parsed.isObject()) {
            return parsed;
        }
        ObjectNode wrapper = Json.object();
        wrapper.set("value", parsed);
        return wrapper;
    }

    /** 取工具结果里的工具名；名字缺失时尝试从合成 id 里还原。 */
    private static String toolNameOf(ToolResultPart toolResult) {
        String name = toolResult.name();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String id = toolResult.toolUseId();
        if (id != null && !id.isBlank()) {
            String value = id.trim();
            // 解码阶段合成的 call_<n> 不含名字，无法还原；编码阶段合成的 <名字>_<序号> 可以。
            if (!value.matches(SYNTHETIC_CALL_PREFIX + "\\d+")) {
                int idx = value.lastIndexOf('_');
                if (idx > 0 && idx < value.length() - 1
                        && value.substring(idx + 1).chars().allMatch(Character::isDigit)) {
                    return value.substring(0, idx);
                }
                return value;
            }
        }
        log.warn("Gemini 编码：工具结果既没有名字也没有可还原的 id，functionResponse.name 退化为 unknown");
        return "unknown";
    }

    /** 编码阶段使用的"名字+序号"稳定 id（仅用于日志与名字还原，不进入请求体）。 */
    private static String synthesizeToolCallId(String name, int sequence) {
        String prefix = name == null || name.isBlank() ? "tool" : name;
        return prefix + "_" + sequence;
    }

    /**
     * 解码阶段合成工具调用 id：Gemini 的 {@code functionCall} 没有 id，而
     * {@link ToolUsePart#id()} 需要一个稳定值用于回调与去重，故按出现顺序合成
     * {@code call_<n>}（从 1 开始）。流式解码器复用同一方法，保证同一个调用在
     * 流式/非流式两条路径上拿到同样的 id。
     */
    static String synthesizeToolCallId(int sequence) {
        return SYNTHETIC_CALL_PREFIX + sequence;
    }

    // ------------------------------------------------------------------ tools

    private static void writeTools(ObjectNode root, ChatRequest request) {
        if (!request.hasTools()) {
            // 无工具时整个 tools 字段省略
            return;
        }
        ArrayNode declarations = root.putArray("tools").addObject().putArray("functionDeclarations");
        for (ToolSpec spec : request.tools()) {
            if (spec == null) {
                continue;
            }
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", spec.name());
            declaration.put("description", spec.description() == null ? "" : spec.description());
            JsonNode schema = spec.inputSchema();
            if (schema != null && !schema.isNull()) {
                declaration.set("parameters", sanitizeSchema(schema));
            }
            // schema 为 null 时索性省略 parameters：Gemini 允许无参函数声明，
            // 硬塞一个假 schema 反而会误导模型。
        }
    }

    private static void writeToolConfig(ObjectNode root, ChatRequest request) {
        String choice = request.toolChoice();
        boolean explicit = choice != null && !choice.isBlank();
        if (!request.hasTools() && (!explicit || "auto".equalsIgnoreCase(choice))) {
            // 没有任何 functionDeclarations 却下发 functionCallingConfig，Gemini 会直接 400。
            // 无工具请求的 toolChoice 通常是 null，省略它与下发 AUTO 对模型而言完全等价。
            return;
        }
        if (!request.hasTools()) {
            log.warn("Gemini 编码：toolChoice={} 但没有声明任何工具，请求可能被上游拒绝", choice);
        }
        ObjectNode config = root.putObject("toolConfig").putObject("functionCallingConfig");
        if (!explicit || "auto".equalsIgnoreCase(choice)) {
            config.put("mode", "AUTO");
        } else if ("none".equalsIgnoreCase(choice)) {
            config.put("mode", "NONE");
        } else if ("required".equalsIgnoreCase(choice) || "any".equalsIgnoreCase(choice)) {
            config.put("mode", "ANY");
        } else {
            // 具体工具名（本心的扩展用法）：强制调用该工具
            config.put("mode", "ANY");
            config.putArray("allowedFunctionNames").add(choice);
        }
    }

    // ------------------------------------------------------------------ generationConfig

    private static void writeGenerationConfig(ObjectNode root, ChatRequest request) {
        ObjectNode generation = Json.object();
        if (request.temperature() != null) {
            generation.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            // Gemini 用 maxOutputTokens 表达输出上限，没有 OpenAI 的 max_tokens 字段
            generation.put("maxOutputTokens", request.maxTokens());
        }
        if (request.topP() != null) {
            generation.put("topP", request.topP());
        }
        List<String> stop = request.stop();
        if (stop != null && stop.stream().anyMatch(s -> s != null && !s.isEmpty())) {
            ArrayNode sequences = generation.putArray("stopSequences");
            stop.stream().filter(s -> s != null && !s.isEmpty()).forEach(sequences::add);
        }
        if (!generation.isEmpty()) {
            root.set("generationConfig", generation);
        }
    }

    private static void applyExtra(ObjectNode root, ChatRequest request) {
        Map<String, Object> extra = request.extra();
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            JsonNode value = Json.valueToTree(entry.getValue());
            if (value == null) {
                continue;
            }
            // 覆盖式写入顶层：同名键（例如 generationConfig、safetySettings）会整体替换上面生成的值，
            // 便于使用方下发 thinkingConfig 等 Gemini 专有配置。
            root.set(entry.getKey(), value);
        }
    }

    // ------------------------------------------------------------------ schema 清洗

    /**
     * 递归剔除 Gemini 不认识的 JSON Schema 关键字。
     *
     * <p>清洗是必须的：上游产出的标准 JSON Schema 常带 {@code additionalProperties}、
     * {@code default}、{@code title}、{@code $schema} 等，Gemini 收到会返回 400。</p>
     */
    private static JsonNode sanitizeSchemaNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return Json.object();
        }
        if (node.isArray()) {
            ArrayNode array = Json.array();
            for (JsonNode child : node) {
                array.add(sanitizeSchemaNode(child));
            }
            return array;
        }
        if (!node.isObject()) {
            return node.deepCopy();
        }
        ObjectNode cleaned = Json.object();
        // 用 properties()（Jackson 2.19 起推荐）而不是已废弃的 fields()
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            if (UNSUPPORTED_SCHEMA_KEYS.contains(key)) {
                continue;
            }
            JsonNode value = field.getValue();
            if (SCHEMA_MAP_KEYS.contains(key) && value != null && value.isObject()) {
                // properties 的键是用户自定义的参数名（完全可能就叫 title / default），
                // 不能当关键字剔除，只对它的值（子 schema）继续清洗
                ObjectNode map = Json.object();
                for (Map.Entry<String, JsonNode> child : value.properties()) {
                    map.set(child.getKey(), sanitizeSchemaNode(child.getValue()));
                }
                cleaned.set(key, map);
            } else if (value != null && (value.isObject() || value.isArray())) {
                cleaned.set(key, sanitizeSchemaNode(value));
            } else if (value != null) {
                cleaned.set(key, value.deepCopy());
            }
        }
        return cleaned;
    }

    /** 清洗入口，并处理 Gemini 对"空对象 schema"的额外限制。 */
    private static JsonNode sanitizeSchema(JsonNode schema) {
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return Json.object().put("type", "string");
        }
        JsonNode cleaned = sanitizeSchemaNode(schema);
        if (!cleaned.isObject()) {
            return Json.object().put("type", "string");
        }
        ObjectNode object = (ObjectNode) cleaned;
        JsonNode properties = object.get("properties");
        // Gemini 不接受 properties 为空（或缺失）的 object schema（会 400），
        // 而无参工具生成的 schema 恰好就是这样，退化成 string 至少能让请求发出去。
        if ("object".equalsIgnoreCase(object.path("type").asText())
                && (properties == null || !properties.isObject() || properties.isEmpty())) {
            ObjectNode degraded = Json.object().put("type", "string");
            if (object.hasNonNull("description")) {
                degraded.set("description", object.get("description"));
            }
            return degraded;
        }
        return object;
    }

    // ------------------------------------------------------------------ decode

    @Override
    public ChatResponse decode(String responseBody, ModelConfig config) {
        JsonNode root = parseResponse(responseBody);
        rejectApiError(root, responseBody);

        JsonNode candidate = firstCandidate(root);
        JsonNode promptFeedback = field(root, "promptFeedback");
        String blockReason = stringOf(promptFeedback, "blockReason");
        if (candidate == null && (blockReason == null || blockReason.isBlank())) {
            throw new ModelException("Gemini 响应缺少 candidates: " + Json.abbreviate(responseBody));
        }

        List<ContentPart> parts = new ArrayList<>();
        FinishReason finishReason = FinishReason.UNKNOWN;
        if (candidate != null) {
            finishReason = FinishReason.fromWire(stringOf(candidate, "finishReason"));
            parts = decodeParts(field(candidate.path("content"), "parts"));
        }
        if (blockReason != null && !blockReason.isBlank()) {
            // 提示词被安全策略拦截时没有 candidate，用 promptFeedback 兜底成"内容过滤"
            finishReason = FinishReason.CONTENT_FILTER;
        }

        ChatResponse.Builder builder = ChatResponse.builder()
                .message(ChatMessage.assistant(parts))
                .finishReason(finishReason)
                .usage(decodeUsage(field(root, "usageMetadata")));

        String responseId = stringOf(root, "responseId");
        if (responseId != null) {
            builder.id(responseId);
        }
        String modelVersion = stringOf(root, "modelVersion");
        builder.model(modelVersion != null ? modelVersion : (config == null ? null : config.model()));

        String rawFinish = stringOf(candidate, "finishReason");
        if (rawFinish != null) {
            builder.raw("finishReason", rawFinish);
        }
        JsonNode safetyRatings = candidate == null ? null : candidate.get("safetyRatings");
        if (safetyRatings != null && !safetyRatings.isNull()) {
            builder.raw("safetyRatings", toRawValue(safetyRatings));
        }
        if (promptFeedback != null && !promptFeedback.isNull()) {
            builder.raw("promptFeedback", toRawValue(promptFeedback));
            if (blockReason != null) {
                builder.raw("blockReason", blockReason);
            }
        }
        return builder.build();
    }

    /** 解析 candidates[0].content.parts。 */
    private static List<ContentPart> decodeParts(JsonNode partsNode) {
        List<ContentPart> parts = new ArrayList<>();
        if (partsNode == null || !partsNode.isArray()) {
            return parts;
        }
        int callSequence = 0;
        for (JsonNode part : partsNode) {
            if (part == null || !part.isObject()) {
                continue;
            }
            String text = stringOf(part, "text");
            if (text != null) {
                // thought=true 的文本是思维链，归一成 ThinkingPart
                parts.add(part.path("thought").asBoolean(false) ? new ThinkingPart(text) : new TextPart(text));
                continue;
            }
            JsonNode functionCall = part.get("functionCall");
            if (functionCall != null && functionCall.isObject()) {
                callSequence++;
                JsonNode args = functionCall.get("args");
                // args 是对象，反向序列化成本心的 argumentsJson 文本
                String argumentsJson = args == null || args.isNull() ? "{}" : Json.write(args);
                parts.add(new ToolUsePart(synthesizeToolCallId(callSequence),
                        functionCall.path("name").asText(""), argumentsJson));
            }
        }
        return parts;
    }

    /** {@code usageMetadata} → {@link Usage}；流式解码器复用同一映射。 */
    static Usage decodeUsage(JsonNode usageMetadata) {
        if (usageMetadata == null || !usageMetadata.isObject()) {
            return Usage.ZERO;
        }
        return new Usage(
                intOf(usageMetadata, "promptTokenCount"),
                intOf(usageMetadata, "candidatesTokenCount"),
                intOf(usageMetadata, "cachedContentTokenCount"),
                intOf(usageMetadata, "thoughtsTokenCount"));
    }

    private static JsonNode parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new ModelException("Gemini 响应体为空");
        }
        JsonNode root;
        try {
            root = Json.parse(responseBody);
        } catch (RuntimeException e) {
            throw new ModelException("Gemini 响应不是合法 JSON: " + Json.abbreviate(responseBody), e);
        }
        if (root == null || !root.isObject()) {
            throw new ModelException("Gemini 响应结构非法（顶层应为 JSON 对象）: "
                    + Json.abbreviate(responseBody));
        }
        return root;
    }

    /** 有些网关会用 HTTP 200 包裹 {@code {"error":{...}}}，这里统一转成 ModelException。 */
    private static void rejectApiError(JsonNode root, String responseBody) {
        JsonNode error = root.get("error");
        if (error == null || error.isNull()) {
            return;
        }
        String message = error.isObject()
                ? error.path("message").asText(Json.write(error)) : error.asText();
        int code = error.isObject() ? error.path("code").asInt(0) : 0;
        throw new ModelException("Gemini 返回错误" + (code > 0 ? "(HTTP " + code + ")" : "") + ": " + message
                + " | " + Json.abbreviate(responseBody), code, null);
    }

    // ------------------------------------------------------------------ 共享小工具（流式解码器复用）

    /** 取候选回答；Gemini 可能返回多个 candidate，本心只取第一个。 */
    static JsonNode firstCandidate(JsonNode root) {
        JsonNode candidates = field(root, "candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        JsonNode first = candidates.get(0);
        return first != null && first.isObject() ? first : null;
    }

    static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value;
    }

    /** 取文本字段；字段缺失或不是标量时返回 null。 */
    static String stringOf(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value == null || !value.isValueNode() ? null : value.asText();
    }

    static int intOf(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value == null || !value.isNumber() ? 0 : Math.max(0, value.asInt());
    }

    private static Object toRawValue(JsonNode node) {
        return node == null ? null : Json.mapper().convertValue(node, Object.class);
    }

    // ------------------------------------------------------------------ stream

    @Override
    public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        return new GeminiStreamDecoder(config, handler);
    }
}
