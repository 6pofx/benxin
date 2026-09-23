package com.benxin.llm.core.tool.builtin;

import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 待办清单工具（{@code todo_write}）。
 *
 * <p><b>为什么这个看起来"没用"的工具是 claude-code 循环的关键：</b>长任务里模型最容易犯的错
 * 不是不会写代码，而是跑着跑着忘了自己要干什么——聊到第 30 步时，最初那份 5 步计划早已滑出
 * 上下文窗口。让模型把计划写进一份显式的清单，这份清单就会以工具结果的形式反复出现在上下文里，
 * 每隔几步提醒它一次"现在是第 3 步，还剩 2 步"。它同时是给用户看的进度条，
 * 也是模型自己对抗"任务漂移"的锚点。</p>
 *
 * <p>清单存在 {@link ToolContext#attributes()} 的 {@value #ATTRIBUTE_KEY} 键下
 * （类型为 {@code List<Map<String,Object>>}），同一个 Agent 运行中的所有工具与循环
 * 都能读到它；存完之后返回渲染后的文本，让模型看到当前的完整状态。</p>
 */
public class TodoWriteTool implements ToolCallback {

    public static final String TOOL_NAME = "todo_write";

    /** 运行属性键名。带 {@code benxin.} 前缀避免与宿主应用的自定义属性撞车。 */
    public static final String ATTRIBUTE_KEY = "benxin.todos";

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";

    private final ToolSpec spec;

    public TodoWriteTool() {
        this.spec = buildSpec();
    }

    private static ToolSpec buildSpec() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode todos = properties.putObject("todos");
        todos.put("type", "array");
        todos.put("description", "完整的待办清单（每次都要传全量，而不是增量）");
        ObjectNode items = todos.putObject("items");
        items.put("type", "object");
        ObjectNode itemProperties = items.putObject("properties");
        ObjectNode content = itemProperties.putObject("content");
        content.put("type", "string");
        content.put("description", "这条待办的内容（祈使句，例如「为 PathSandbox 补测试」）");
        ObjectNode status = itemProperties.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add(STATUS_PENDING).add(STATUS_IN_PROGRESS).add(STATUS_COMPLETED);
        status.put("description", "状态：pending / in_progress / completed");
        items.putArray("required").add("content").add("status");
        items.put("additionalProperties", false);

        schema.putArray("required").add("todos");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "创建/更新当前任务的待办清单并返回渲染结果。"
                        + "多步任务应当先写清单，每完成一步就更新一次——这是长任务不跑偏的主要手段。"
                        + "每次传全量清单；同一时刻最多一条 in_progress。",
                schema);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult call(Map<String, Object> arguments, ToolContext context) {
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            Object raw = args.get("todos");
            if (raw == null) {
                return ToolResult.error("参数 todos 必填：请给出完整的待办清单数组"
                        + "（每项含 content 与 status，status ∈ pending/in_progress/completed）。");
            }

            List<Map<String, Object>> todos = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            int index = 0;
            for (Object item : asList(raw)) {
                index++;
                Map<String, Object> map = asMap(item);
                if (map == null) {
                    problems.add("第 " + index + " 项不是对象（应为 {content, status}）");
                    continue;
                }
                String content = asString(map.get("content"));
                String status = normalizeStatus(asString(map.get("status")));
                if (content == null || content.isBlank()) {
                    problems.add("第 " + index + " 项缺少 content");
                    continue;
                }
                if (status == null) {
                    problems.add("第 " + index + " 项 status 非法: " + map.get("status")
                            + "（只允许 pending / in_progress / completed）");
                    continue;
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("content", content.trim());
                normalized.put("status", status);
                todos.add(normalized);
            }
            if (!problems.isEmpty()) {
                return ToolResult.error("待办清单格式有误：" + String.join("；", problems)
                        + "。正确格式示例：{\"todos\":[{\"content\":\"...\",\"status\":\"in_progress\"}]}");
            }

            String rendered = render(todos);
            if (context != null) {
                try {
                    context.attributes().put(ATTRIBUTE_KEY, List.copyOf(todos));
                } catch (UnsupportedOperationException e) {
                    return ToolResult.error("无法保存待办清单：当前运行上下文的属性表只读，"
                            + "不支持写入 " + ATTRIBUTE_KEY + "。\n" + rendered);
                }
                try {
                    context.emit("todos.updated", List.copyOf(todos));
                } catch (RuntimeException ignored) {
                    // 事件广播失败不影响清单本身
                }
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("total", todos.size());
            meta.put("completed", todos.stream()
                    .filter(item -> STATUS_COMPLETED.equals(item.get("status"))).count());
            meta.put("inProgress", todos.stream()
                    .filter(item -> STATUS_IN_PROGRESS.equals(item.get("status"))).count());
            return ToolResult.ok(rendered, meta);
        } catch (RuntimeException e) {
            return ToolResult.error("更新待办清单时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        // 它会写运行属性（共享状态），并行执行会让最终清单取决于竞态。
        return false;
    }

    @Override
    public boolean requiresApproval() {
        return false; // 只动内存里的清单，不碰磁盘
    }

    /** 渲染清单：{@code [ ]} 待办、{@code [~]} 进行中、{@code [x]} 已完成，并统计完成度。 */
    private static String render(List<Map<String, Object>> todos) {
        if (todos.isEmpty()) {
            return "待办清单已清空（当前没有未完成的任务）。";
        }
        int completed = 0;
        int inProgress = 0;
        int pending = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < todos.size(); i++) {
            Map<String, Object> item = todos.get(i);
            String status = String.valueOf(item.get("status"));
            String mark;
            switch (status) {
                case STATUS_COMPLETED -> {
                    mark = "[x]";
                    completed++;
                }
                case STATUS_IN_PROGRESS -> {
                    mark = "[~]";
                    inProgress++;
                }
                default -> {
                    mark = "[ ]";
                    pending++;
                }
            }
            out.append(mark).append(' ').append(i + 1).append(". ")
                    .append(item.get("content")).append('\n');
        }
        int total = todos.size();
        int percent = (int) Math.round(completed * 100.0 / total);
        out.append('\n').append("完成度: ").append(completed).append('/').append(total)
                .append(" (").append(percent).append("%)");
        out.append("，进行中 ").append(inProgress).append(" 项");
        out.append("，待办 ").append(pending).append(" 项。");
        if (completed == total) {
            out.append("\n全部任务已完成，可以收尾了。");
        } else if (inProgress == 0) {
            out.append("\n提示：把下一条要做的改成 in_progress 再继续。");
        }
        return out.toString();
    }

    /** 宽容归一化：允许 DONE / TODO / in-progress 这类常见写法。 */
    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (value) {
            case "pending", "todo", "not_started", "open" -> STATUS_PENDING;
            case "in_progress", "inprogress", "doing", "active", "started" -> STATUS_IN_PROGRESS;
            case "completed", "complete", "done", "finished", "closed" -> STATUS_COMPLETED;
            default -> null;
        };
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof JsonNode node && node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            List<Object> items = new ArrayList<>();
            array.forEach(items::add);
            return items;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof JsonNode node && node.isObject()) {
            return Json.toMap(node);
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return "TodoWriteTool";
    }
}
