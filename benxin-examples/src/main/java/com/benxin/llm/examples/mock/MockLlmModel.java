package com.benxin.llm.examples.mock;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线可跑的假模型：不联网、不花钱，却能完整驱动 Agent 循环。
 *
 * <p>它用一组明确的规则模拟"模型决策"，规则本身也顺便成了框架行为的说明书：</p>
 * <ol>
 *   <li>用户消息里出现 {@code #tool:<名字> <JSON参数>} → 发起指定的工具调用；</li>
 *   <li>还没有任何工具结果、且请求里带了工具、且用户在问需要外部信息的问题
 *       （含"天气"、"汇率"、"算"等关键词）→ 调用最匹配的一个工具；</li>
 *   <li>已经有工具结果 → 基于结果组织最终回答；</li>
 *   <li>提示词里要求 JSON → 返回一段结构化 JSON（用于演示声明式 DTO 返回值）；</li>
 *   <li>其它情况 → 返回一段说明文字。</li>
 * </ol>
 *
 * <p>流式接口被显式覆写，按字符分片推送，因此 {@code /llm/agents/{name}/stream} 端点
 * 即使在这个假模型下也能演示出真实的打字机效果。</p>
 */
public class MockLlmModel implements LlmModel {

    private static final Logger log = LoggerFactory.getLogger(MockLlmModel.class);

    private static final Pattern FORCED_TOOL = Pattern.compile("#tool:([A-Za-z0-9_\\-]+)\\s*(\\{.*?})?",
            Pattern.DOTALL);

    private final String name;
    private final AtomicInteger calls = new AtomicInteger();

    public MockLlmModel() {
        this("mock");
    }

    public MockLlmModel(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.builder()
                .streaming(true)
                .toolCalling(true)
                .parallelToolCalls(true)
                .maxContextTokens(32_000)
                .build();
    }

    /** 便于示例打印"这个模型被调了多少次"。 */
    public int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int index = calls.incrementAndGet();
        String userText = lastUserText(request);
        String systemText = request.systemText();
        boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == Role.TOOL);

        log.info("[mock] 第 {} 次调用：历史 {} 条，工具 {} 个，已有工具结果={}",
                index, request.messages().size(), request.tools().size(), hasToolResult);

        // 规则 1：用户显式指定了工具
        Matcher forced = FORCED_TOOL.matcher(userText);
        if (forced.find() && hasToolFor(request, forced.group(1))) {
            String args = forced.group(2) == null ? "{}" : forced.group(2);
            return toolCall(forced.group(1), args);
        }

        // 规则 2：需要外部信息且尚未取过
        if (!hasToolResult && request.hasTools()) {
            ToolSpec picked = pickTool(request.tools(), userText);
            if (picked != null) {
                return toolCall(picked.name(), guessArguments(picked, userText));
            }
        }

        // 规则 3：基于工具结果作答
        if (hasToolResult) {
            return text(answerFromToolResults(request));
        }

        // 规则 4：要求 JSON（演示声明式接口的 DTO 返回值）
        if (containsJsonHint(systemText) || containsJsonHint(userText)) {
            return text("""
                    {
                      "summary": "示例代码整体结构清晰，但存在两处可以改进的地方。",
                      "issues": ["缺少对空入参的校验", "异常被吞掉，建议至少记录日志"],
                      "score": 78
                    }""");
        }

        // 规则 5：兜底
        return text("（Mock 模型）我收到了你的请求：" + abbreviate(userText, 60)
                + "。当前没有可用的外部信息，因此直接作答。想让我调用工具，可以写成 "
                + "#tool:<工具名> {\"参数\":\"值\"} 的形式。");
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        handler.onStart();
        ChatResponse response = chat(request);
        String content = response.message().text();
        if (!content.isEmpty()) {
            // 按 12 个字符一片推送，模拟真实模型的打字机效果
            int chunk = 12;
            for (int i = 0; i < content.length(); i += chunk) {
                handler.onTextDelta(content.substring(i, Math.min(content.length(), i + chunk)));
            }
        }
        response.message().toolUses().forEach(handler::onToolCall);
        handler.onUsage(response.usage());
        handler.onComplete(response);
    }

    // ------------------------------------------------------------------

    private static ChatResponse toolCall(String toolName, String argumentsJson) {
        return ChatResponse.builder()
                .id("mock-" + System.nanoTime())
                .model("mock")
                .message(ChatMessage.assistant("", List.of(
                        new ToolUsePart("call_" + Math.abs(toolName.hashCode()), toolName, argumentsJson))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(new Usage(120, 40))
                .build();
    }

    private static ChatResponse text(String content) {
        return ChatResponse.builder()
                .id("mock-" + System.nanoTime())
                .model("mock")
                .message(ChatMessage.assistant(content))
                .finishReason(FinishReason.STOP)
                .usage(new Usage(180, 90))
                .build();
    }

    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i).text();
            }
        }
        return "";
    }

    private static boolean hasToolFor(ChatRequest request, String name) {
        return request.tools().stream().anyMatch(t -> t.name().equals(name));
    }

    /** 关键词 → 工具名片段的最简匹配。 */
    private static ToolSpec pickTool(List<ToolSpec> tools, String userText) {
        record Hint(String keyword, String toolFragment) {
        }
        List<Hint> hints = List.of(
                new Hint("天气", "weather"),
                new Hint("气温", "weather"),
                new Hint("汇率", "rate"),
                new Hint("算", "calc"),
                new Hint("计算", "calc"),
                new Hint("现在", "time"),
                new Hint("时间", "time"),
                new Hint("搜索", "search"));
        for (Hint hint : hints) {
            if (userText.contains(hint.keyword())) {
                for (ToolSpec tool : tools) {
                    if (tool.name().toLowerCase().contains(hint.toolFragment())) {
                        return tool;
                    }
                }
            }
        }
        return null;
    }

    /** 按工具的 schema 造一份最小可用参数，让 demo 无需人工干预即可跑通。 */
    private static String guessArguments(ToolSpec tool, String userText) {
        var schema = tool.inputSchema();
        var node = Json.object();
        if (schema != null && schema.has("properties")) {
            schema.get("properties").fieldNames().forEachRemaining(field -> {
                var type = schema.get("properties").get(field).path("type").asText("string");
                switch (type) {
                    case "integer", "number" -> node.put(field, 2);
                    case "boolean" -> node.put(field, true);
                    case "array" -> node.putArray(field).add("示例");
                    default -> node.put(field, defaultString(field, userText));
                }
            });
        }
        return node.toString();
    }

    private static String defaultString(String field, String userText) {
        if (field.toLowerCase().contains("city") || field.contains("城市")) {
            Matcher city = Pattern.compile("([\\u4e00-\\u9fa5]{2,8}?)(?:的)?(?:天气|气温)").matcher(userText);
            if (city.find()) {
                return city.group(1);
            }
            return "北京";
        }
        // 常见枚举型参数给一个合理默认值，避免把整句用户输入塞进 unit / language 这类字段
        String known = FIELD_DEFAULTS.get(field.toLowerCase().replace("_", ""));
        if (known != null) {
            return known;
        }
        return userText.isBlank() ? "示例" : abbreviate(userText, 20);
    }

    /** 常见参数的合理默认值：让离线演示的输出读起来像话。 */
    private static final java.util.Map<String, String> FIELD_DEFAULTS = java.util.Map.ofEntries(
            java.util.Map.entry("unit", "celsius"),
            java.util.Map.entry("language", "中文"),
            java.util.Map.entry("lang", "中文"),
            java.util.Map.entry("targetlanguage", "英文"),
            java.util.Map.entry("style", "简洁"),
            java.util.Map.entry("audience", "开发者"),
            java.util.Map.entry("format", "text"),
            java.util.Map.entry("subagenttype", "task"));

    private static String answerFromToolResults(ChatRequest request) {
        StringBuilder sb = new StringBuilder("根据工具返回的结果：\n");
        request.messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .forEach(m -> sb.append("- ").append(m.text()).append('\n'));
        sb.append("\n综合来看，这就是你要的答案。（本回答由离线 Mock 模型生成，"
                + "切换到 real profile 即可让真实模型接管。）");
        return sb.toString();
    }

    private static boolean containsJsonHint(String text) {
        return text != null && (text.contains("JSON") || text.contains("json"));
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}