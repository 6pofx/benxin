package com.benxin.llm.core.tool.builtin;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 子代理工具（{@code task}）：把一个独立任务甩给隔离子代理去跑，只把结论拿回来。
 *
 * <p><b>为什么"只回摘要"是精髓而不是偷懒：</b>子代理探索目录、读二十个文件、试三种方案，
 * 这些过程如果全塞回主上下文，主 agent 会在几步之内被细节淹没，还烧掉大量 token。
 * 让子代理在自己的上下文里做完脏活，只回传一段结论——主上下文保持短小清醒，
 * 这正是 claude-code 子代理设计的核心价值。因此本工具返回的永远是
 * {@link AgentResult#text()} 这段摘要，而不是子代理的完整对话历史。</p>
 *
 * <p>子代理类型从参数 {@code subagent_type} 取；没给时回退到运行属性
 * {@value #DEFAULT_SUBAGENT_KEY}（便于宿主应用给某次运行设一个默认子代理）。</p>
 */
public class TaskTool implements ToolCallback {

    public static final String TOOL_NAME = "task";

    /** 运行属性键：默认子代理名。 */
    public static final String DEFAULT_SUBAGENT_KEY = "benxin.default-subagent";

    /** 传给子代理的运行属性键：任务简述。 */
    public static final String TASK_DESCRIPTION_KEY = "benxin.task.description";

    /** 传给子代理的运行属性键：发起方 Agent 名。 */
    public static final String PARENT_AGENT_KEY = "benxin.parent.agent";

    /** 传给子代理的运行属性键：父会话 id。 */
    public static final String PARENT_SESSION_KEY = "benxin.parent.session";

    private final ToolSpec spec;

    public TaskTool() {
        this.spec = buildSpec();
    }

    private static ToolSpec buildSpec() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode description = properties.putObject("description");
        description.put("type", "string");
        description.put("description", "任务的简短描述（3-5 个词），用于日志与进度展示");

        ObjectNode prompt = properties.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "交给子代理的完整任务说明。子代理看不到当前对话，"
                + "请把它需要知道的一切都写进去");

        ObjectNode subagentType = properties.putObject("subagent_type");
        subagentType.put("type", "string");
        subagentType.put("description", "可选：使用哪个子代理（需在 Agent 配置中已声明）");

        schema.putArray("required").add("description").add("prompt");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "派生子代理执行一个独立任务，只返回它的结论摘要。"
                        + "适合「探索一大片目录」「多方案调研」这类会产生大量中间过程、"
                        + "但只需要一个答案的活儿。",
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
            String description = asString(args.get("description"));
            String prompt = asString(args.get("prompt"));
            if (description == null || description.isBlank()) {
                return ToolResult.error("参数 description 必填：请给出一句话的任务简述。");
            }
            if (prompt == null || prompt.isBlank()) {
                return ToolResult.error("参数 prompt 必填：请给出交给子代理的完整任务说明。");
            }
            if (context == null) {
                return ToolResult.error("当前环境没有工具上下文，无法派生子代理。");
            }
            if (!context.canSpawnSubAgent()) {
                return ToolResult.error("当前 Agent 未配置子代理能力，无法执行 task 工具。"
                        + "请在 AgentSpec.subAgents 里声明可用的子代理后重试"
                        + "（子代理是隔离上下文执行的，只在需要「独立探索 + 只回结论」时才值得用）。");
            }

            String subAgentName = resolveSubAgentName(args, context);
            if (subAgentName == null) {
                return ToolResult.error("未指定子代理类型：请在参数 subagent_type 中给出子代理名，"
                        + "或在运行属性 " + DEFAULT_SUBAGENT_KEY + " 中配置一个默认子代理。");
            }

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put(TASK_DESCRIPTION_KEY, description.trim());
            extra.put(PARENT_AGENT_KEY, context.agentName());
            extra.put(PARENT_SESSION_KEY, context.sessionId());

            AgentResult result;
            try {
                Optional<AgentResult> spawned = context.spawn(subAgentName, prompt, extra);
                if (spawned == null || spawned.isEmpty()) {
                    return ToolResult.error("派生子代理 [" + subAgentName + "] 失败："
                            + "上下文未返回结果（该子代理可能未在 Agent 配置中声明）。");
                }
                result = spawned.get();
            } catch (RuntimeException e) {
                return ToolResult.error("派生子代理 [" + subAgentName + "] 失败: "
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            }

            String summary = result.text() == null ? "" : result.text().trim();
            if (summary.isEmpty()) {
                summary = "（子代理 [" + subAgentName + "] 没有返回文字结论，请换一种更明确的提问方式重试）";
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("description", description.trim());
            meta.put("subAgent", subAgentName);
            meta.put("steps", result.steps());
            meta.put("finishReason", String.valueOf(result.finishReason()));
            meta.put("inputTokens", result.usage().inputTokens());
            meta.put("outputTokens", result.usage().outputTokens());
            meta.put("durationMillis", result.durationMillis());
            meta.put("maxStepsReached", result.maxStepsReached());

            try {
                context.emit("tool.task", Map.of(
                        "subAgent", subAgentName,
                        "description", description.trim(),
                        "steps", result.steps()));
            } catch (RuntimeException ignored) {
                // 事件广播失败不影响结果
            }

            StringBuilder out = new StringBuilder();
            out.append("[子代理 ").append(subAgentName).append(" 的结论] ").append(description.trim())
                    .append('\n').append(summary);
            if (result.maxStepsReached()) {
                out.append("\n（注意：子代理因达到步数上限而中断，结论可能不完整）");
            }
            return ToolResult.ok(out.toString(), meta);
        } catch (RuntimeException e) {
            return ToolResult.error("执行 task 工具时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        // 子代理之间互相独立，允许同一批里并行派生（框架的 spawn 路径已对共享属性加了锁）。
        return true;
    }

    @Override
    public boolean requiresApproval() {
        // 派生子代理本身不改磁盘；子代理内部若调用 write / bash，会在它自己的上下文里触发审批。
        return false;
    }

    private static String resolveSubAgentName(Map<String, Object> args, ToolContext context) {
        String explicit = asString(args.get("subagent_type"));
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        Object fallback = context.attributes() == null ? null : context.attributes().get(DEFAULT_SUBAGENT_KEY);
        String name = asString(fallback);
        return name == null || name.isBlank() ? null : name.trim();
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof com.fasterxml.jackson.databind.JsonNode node) {
            return node.isNull() || node.isMissingNode() ? null : node.asText();
        }
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return "TaskTool";
    }
}
