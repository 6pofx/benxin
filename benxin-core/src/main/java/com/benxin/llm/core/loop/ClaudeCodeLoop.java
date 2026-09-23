package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.prompt.PromptTemplates;
import com.benxin.llm.core.prompt.PromptTemplates.PromptContext;
import com.benxin.llm.core.util.TokenEstimator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * claude-code Loop：把 Claude Code 的主循环用 Java 还原。
 *
 * <p>设计来源：Claude Code 的 Agent 主循环——"系统提示词交代环境与规范 → 模型自行决定用哪些工具
 * → 边做边维护待办清单 → 上下文过长时自动压缩 → 把检索类子任务丢给子代理"。本类只做 Loop 该做的事：
 * 每一轮调用前注入高信号的提醒（待办、错误熔断、压缩），其余决策权全部交给模型。</p>
 *
 * <p>关键取舍：</p>
 * <ol>
 *   <li><b>不在 Loop 里抢系统提示词</b>：系统提示词属于 Agent 配置（{@code @LlmAgent(systemPrompt=...)}
 *       或外部 {@code SystemPromptProvider}），用户可能已经写好了自己的版本。本 Loop 只在
 *       {@code ctx.systemPrompt()} 为空时兜底，且兜底内容以 user 消息形式注入——
 *       因为 {@code LoopContext} 没有提供"改写系统提示词"的能力，而默认
 *       {@code DefaultContextManager} 又会丢弃历史里的 SYSTEM 消息（它只从
 *       {@code prepare(systemPrompt, ...)} 的参数取系统提示），把兜底塞成 SYSTEM 消息等于静默无效。
 *       这个取舍的代价是兜底提示在历史里显示为一条 user 消息，好处是它与任何 ContextManager 都能配合。</li>
 *   <li><b>上下文压缩在 Loop 层做</b>：{@code ContextManager} 只在"组装这一次请求"时生效，
 *       它压缩的是下发给模型的副本，{@code ctx.messages()} 本身仍会无限增长——长任务里最终会把
 *       内存和用量统计一起拖垮。因此本 Loop 每 {@code compactThresholdSteps} 步检查一次 token
 *       估算值，把较早的工具结果就地改写成占位摘要。工具结果通常是历史里体积最大、
 *       且"结论已被消化"的部分，压缩它收益最高、信息损失最小。</li>
 *   <li><b>熔断而非重试</b>：连续 {@code maxConsecutiveToolErrors} 次工具失败时注入一条提醒让模型
 *       停下来重新评估，而不是替它换参数重试——Loop 不知道失败原因，模型才知道。</li>
 *   <li><b>只依赖工具名，不依赖工具类</b>：待办、子代理、命令类工具全部按名字（字符串）判断，
 *       因此 claude-code 可以和任意工具集组合，这正是"一切可替换"的体现。</li>
 *   <li><b>裸环境可用</b>：没有工具、没有子代理时，本 Loop 依然正常工作（只是不会注入待办提醒、
 *       也不会暴露子代理能力）。</li>
 * </ol>
 */
@LlmLoop("claude-code")
public class ClaudeCodeLoop extends AbstractAgentLoop {

    /** Loop 名。 */
    public static final String NAME = "claude-code";

    /** 首轮环境已注入标记。 */
    private static final String ATTR_INITIALIZED = "benxin.claude-code.initialized";
    /** 本次压掉的工具结果条数。 */
    private static final String ATTR_COMPACTIONS = "benxin.claude-code.compactions";
    /** 本次工具失败总次数。 */
    private static final String ATTR_TOOL_ERRORS = "benxin.claude-code.toolErrors";
    /** 本次执行步数。 */
    private static final String ATTR_STEPS = "benxin.claude-code.steps";
    /** 首轮注入的可用工具清单。 */
    private static final String ATTR_TOOLS = "benxin.claude-code.tools";
    /** 首轮注入的子代理开关。 */
    private static final String ATTR_SUB_AGENTS = "benxin.claude-code.subAgents";
    /** 首轮注入的工作目录。 */
    private static final String ATTR_CWD = "benxin.claude-code.cwd";

    /** 外部待办工具写给本 Loop 的属性键（由 todo 工具负责写入，本 Loop 只读）。 */
    private static final String ATTR_TODOS = "benxin.todos";

    /** 上下文占用超过模型窗口的这个比例时触发压缩。 */
    private static final double COMPACT_TRIGGER_RATIO = 0.7;

    /** 压缩工具结果时保留的头部字符数。 */
    private static final int COMPACT_KEEP_CHARS = 600;

    /** 压缩占位标记；已带标记的消息不会二次压缩（幂等）。 */
    private static final String COMPACT_MARKER = "…[已压缩]";

    /** 压缩时保留的"最近工具结果"条数：越近的结果越可能还被引用。 */
    private static final int COMPACT_KEEP_RECENT_RESULTS = 6;

    /** 系统提示词兜底已注入标记。 */
    private static final String ATTR_FALLBACK_PROMPT = "benxin.claude-code.fallbackPromptInjected";

    /** 待办提醒的间隔步数。 */
    private static final int TODO_REMINDER_INTERVAL = 4;

    /** 提醒"还没用过待办工具"的步数。 */
    private static final int TODO_NUDGE_STEP = 3;

    /** 派生子代理时使用的默认工具名。 */
    private final String subAgentName;

    /** 每多少步做一次上下文压缩检查。 */
    private final int compactThresholdSteps;

    /** 连续多少次工具失败后注入熔断提醒。 */
    private final int maxConsecutiveToolErrors;

    public ClaudeCodeLoop() {
        this("task", 12, 3);
    }

    public ClaudeCodeLoop(String subAgentName) {
        this(subAgentName, 12, 3);
    }

    /**
     * @param subAgentName             派生"子任务"时使用的子代理名
     * @param compactThresholdSteps    压缩检查的步数间隔（&lt;=0 视为默认 12）
     * @param maxConsecutiveToolErrors 连续失败熔断阈值（&lt;=0 表示关闭熔断）
     */
    public ClaudeCodeLoop(String subAgentName, int compactThresholdSteps, int maxConsecutiveToolErrors) {
        this.subAgentName = subAgentName == null || subAgentName.isBlank() ? "task" : subAgentName.trim();
        this.compactThresholdSteps = compactThresholdSteps <= 0 ? 12 : compactThresholdSteps;
        this.maxConsecutiveToolErrors = maxConsecutiveToolErrors;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Claude Code 风格主循环：计划清单 + 工具探索 + 自动压缩 + 子代理，适合多文件的中长任务";
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        RunState state = new RunState();

        // 1) 首轮注入环境：只做一次，避免多轮对话里反复重复同样的环境描述
        if (!Boolean.TRUE.equals(get(ctx, ATTR_INITIALIZED))) {
            injectEnvironment(ctx);
        }
        // 2) 系统提示词兜底（外部已提供则完全不动）
        applyFallbackSystemPrompt(ctx);

        // 3) 主循环
        LoopResult result = runToolCallingLoop(ctx, current -> stepHook(current, state));

        // 4) 收尾统计
        put(ctx, ATTR_STEPS, ctx.step());
        put(ctx, ATTR_COMPACTIONS, state.compactions);
        put(ctx, ATTR_TOOL_ERRORS, state.totalErrors);
        ctx.emit("claude-code.done", Map.of(
                "steps", ctx.step(),
                "compactions", state.compactions,
                "toolErrors", state.totalErrors,
                "executedTools", List.copyOf(state.executedTools)));
        // LoopResult 在构造时就把 attributes 拷成了快照，而上面的统计是在主循环返回之后才写的。
        // 对标准上下文重新封装一次，保证 LoopResult.attributes() 与 ctx.attributes() 一致；
        // 自定义 LoopContext 的产物不动（它的 LoopResult 可能自带实现特有的字段）。
        return ctx instanceof DefaultLoopContext ? buildResult(ctx, result.text()) : result;
    }

    /**
     * 每步开始前的注入点：压缩 → 待办提醒 → 错误熔断。
     *
     * <p>三类提醒会被合并成一条 user 消息发送：连续插入多条 user 消息在部分协议下不合法，
     * 而且模型对"一条结构化提醒"的遵从度高于"三条零散提醒"。</p>
     */
    private void stepHook(LoopContext ctx, RunState state) {
        scanToolResults(ctx, state);

        // 上下文压缩放在提醒之前：先减体积，再加上提醒
        if (ctx.step() > 1 && ctx.step() % compactThresholdSteps == 0) {
            maybeCompact(ctx, state);
        }

        List<String> reminders = new ArrayList<>();
        String todoReminder = todoReminder(ctx, state);
        if (todoReminder != null) {
            reminders.add(todoReminder);
        }
        String errorReminder = errorCircuitBreaker(ctx, state);
        if (errorReminder != null) {
            reminders.add(errorReminder);
        }
        if (!reminders.isEmpty()) {
            remind(ctx, String.join("\n", reminders));
            ctx.emit("claude-code.reminder", String.join(" | ", reminders));
        }
    }

    // ------------------------------------------------------------------
    // 首轮环境注入
    // ------------------------------------------------------------------

    private void injectEnvironment(LoopContext ctx) {
        List<String> tools = safeToolNames(ctx);
        boolean subAgents = supportsSubAgents(ctx);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cwd", System.getProperty("user.dir", "."));
        payload.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        payload.put("tools", tools);
        payload.put("subAgents", subAgents);
        if (subAgents) {
            payload.put("subAgentName", subAgentName);
        }

        put(ctx, ATTR_INITIALIZED, Boolean.TRUE);
        put(ctx, ATTR_TOOLS, tools);
        put(ctx, ATTR_SUB_AGENTS, subAgents);
        put(ctx, ATTR_CWD, payload.get("cwd"));
        ctx.emit("claude-code.init", payload);
        log.debug("[claude-code] 注入环境：工具 {} 个，子代理 {}，cwd={}",
                tools.size(), subAgents ? "可用" : "不可用", payload.get("cwd"));
    }

    /**
     * 系统提示词兜底。
     *
     * <p>取舍见类注释：本 Loop 不覆盖用户配置的系统提示词；只有它为空时才用
     * {@link PromptTemplates#claudeCode(PromptContext)} 兜底，并以 user 消息注入。</p>
     */
    private void applyFallbackSystemPrompt(LoopContext ctx) {
        String existing = ctx.systemPrompt();
        if (existing != null && !existing.isBlank()) {
            return;
        }
        if (Boolean.TRUE.equals(get(ctx, ATTR_FALLBACK_PROMPT))) {
            return;
        }
        String fallback = PromptTemplates.claudeCode(promptContext(ctx));
        ctx.append(ChatMessage.user("【本心内置系统提示｜claude-code】\n" + fallback));
        put(ctx, ATTR_FALLBACK_PROMPT, Boolean.TRUE);
        ctx.emit("claude-code.system-prompt-fallback", "ctx.systemPrompt() 为空，已注入内置提示词");
        log.debug("[claude-code] 系统提示词为空，已注入内置兜底提示词（{} 字符）", fallback.length());
    }

    private PromptContext promptContext(LoopContext ctx) {
        return new PromptContext(
                System.getProperty("user.dir", "."),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
                LocalDate.now().toString(),
                ctx.model() == null ? null : ctx.model().name(),
                safeToolNames(ctx),
                supportsSubAgents(ctx));
    }

    // ------------------------------------------------------------------
    // 待办提醒
    // ------------------------------------------------------------------

    /**
     * 待办提醒：清单由模型通过待办工具写入 {@code benxin.todos}，本 Loop 只读不写。
     *
     * <p>提醒里点名的是"当前应推进的那一项"：优先取状态为 {@code in_progress} 的条目
     * （本心内置 {@code todo_write} 工具的契约是"同一时刻最多一条 in_progress"），
     * 没有就取第一条未完成的。序号取清单里的真实位置，不是"未完成项里的第几个"。</p>
     *
     * @return 需要注入的提醒文本；不需要注入时返回 {@code null}
     */
    private String todoReminder(LoopContext ctx, RunState state) {
        List<TodoItem> items = allTodos(ctx);
        TodoItem current = currentTodo(items);
        if (current != null) {
            // step() 是"已完成的步数"，第 0 步（模型还没动过）不提醒，避免开局就灌一段噪音
            if (ctx.step() <= 0 || ctx.step() % TODO_REMINDER_INTERVAL != 0) {
                return null;
            }
            List<String> pending = new ArrayList<>();
            for (TodoItem item : items) {
                if (!item.done() && !item.text().isBlank()) {
                    pending.add(item.text());
                }
            }
            int done = items.size() - pending.size();
            return "当前待办：" + String.join("；", preview(pending, 5))
                    + "。请继续推进第 " + current.index() + " 项「" + current.text() + "」"
                    + "（已完成 " + done + "/" + items.size() + "），"
                    + "做完一项就更新待办状态，不要在未验证前标记完成。";
        }
        // 清单压根不存在、工具集里有待办工具、模型却一次都没用过：第 3 步温和提醒一次。
        // 只有在"没有清单"时才提醒——否则会和上面的待办提醒互相打架。
        if (ctx.step() == TODO_NUDGE_STEP
                && !state.todoToolUsed
                && PromptTemplates.hasTodoTool(safeToolNames(ctx))) {
            state.todoToolUsed = true; // 只提醒一次
            String todoTool = PromptTemplates.firstMatchingTool(safeToolNames(ctx), List.of("todo"));
            return "提醒：本次任务还没建立待办清单。若任务涉及多个步骤或多处改动，"
                    + "请先用 " + todoTool + " 列出计划，再逐项推进。";
        }
        return null;
    }

    /** 读出整份待办清单（带序号与状态，格式容错见 {@link #collectTodos}）。 */
    private List<TodoItem> allTodos(LoopContext ctx) {
        Object raw = attributes(ctx).get(ATTR_TODOS);
        if (raw == null) {
            return List.of();
        }
        List<TodoItem> items = new ArrayList<>();
        collectTodos(raw, items, 0);
        return items;
    }

    /** 选出"当前应推进"的一项：优先 in_progress，其次第一条未完成。 */
    private static TodoItem currentTodo(List<TodoItem> items) {
        TodoItem firstPending = null;
        for (TodoItem item : items) {
            if (item.done() || item.text().isBlank()) {
                continue;
            }
            if (item.inProgress()) {
                return item;
            }
            if (firstPending == null) {
                firstPending = item;
            }
        }
        return firstPending;
    }

    /**
     * 容错解析待办清单。
     *
     * <p>待办工具是"可替换"的一环：不同实现可能写 {@code List<Map<String,Object>>}、
     * {@code List<String>}、甚至一段多行文本。本 Loop 不绑定任何具体实现，因此对常见形态逐一兜住；
     * 认不出来的形态一律忽略（绝不抛异常，也绝不因为读不懂待办就中断主循环）。</p>
     */
    private void collectTodos(Object raw, List<TodoItem> out, int depth) {
        if (raw == null || depth > 3 || out.size() > 200) {
            return;
        }
        if (raw instanceof CharSequence text) {
            int index = out.size();
            for (String line : text.toString().split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                index++;
                boolean done = trimmed.startsWith("[x]") || trimmed.startsWith("[X]")
                        || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")
                        || trimmed.startsWith("✅") || trimmed.contains("已完成");
                out.add(new TodoItem(index, stripBullet(trimmed), done, trimmed.contains("进行中")));
            }
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            // 有些实现会把清单包一层，例如 {"todos": [...]}
            for (String key : List.of("todos", "items", "list", "tasks", "data", "steps")) {
                Object nested = map.get(key);
                if (nested instanceof Collection<?> || nested instanceof Map<?, ?>) {
                    collectTodos(nested, out, depth + 1);
                    if (!out.isEmpty()) {
                        return;
                    }
                }
            }
            String text = firstString(map, "content", "text", "task", "title", "description", "subject", "step");
            if (text != null) {
                Object status = map.get("status") != null ? map.get("status") : map.get("state");
                out.add(new TodoItem(out.size() + 1, text,
                        isDone(status) || isDone(map.get("done")) || isDone(map.get("completed")),
                        isInProgress(status)));
            }
            return;
        }
        if (raw instanceof Collection<?> collection) {
            for (Object element : collection) {
                collectTodos(element, out, depth + 1);
            }
            return;
        }
        out.add(new TodoItem(out.size() + 1, String.valueOf(raw), false, false));
    }

    private static String firstString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).strip();
            }
        }
        return null;
    }

    /** 判断待办条目是否"进行中"（本心内置 todo_write 用 {@code in_progress}）。 */
    private static boolean isInProgress(Object status) {
        if (status == null) {
            return false;
        }
        String value = String.valueOf(status).strip().toLowerCase();
        return value.equals("in_progress") || value.equals("in-progress") || value.equals("inprogress")
                || value.equals("doing") || value.equals("active") || value.equals("started")
                || value.equals("进行中");
    }

    /** 判断待办条目的"完成"状态，兼顾英文枚举与中文状态。 */
    private static boolean isDone(Object status) {
        if (status == null) {
            return false;
        }
        if (status instanceof Boolean bool) {
            return bool;
        }
        String value = String.valueOf(status).strip().toLowerCase();
        return value.equals("true") || value.equals("completed") || value.equals("complete")
                || value.equals("done") || value.equals("finished") || value.equals("closed")
                || value.equals("已完成") || value.equals("完成") || value.equals("已取消")
                || value.equals("cancelled") || value.equals("canceled");
    }

    private static String stripBullet(String text) {
        String value = text;
        if (value.startsWith("- ")) {
            value = value.substring(2).strip();
        }
        if (value.startsWith("[ ]") || value.startsWith("[x]") || value.startsWith("[X]")) {
            value = value.substring(3).strip();
        }
        if (value.startsWith("✅")) {
            value = value.substring(1).strip();
        }
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }

    private static List<String> preview(List<String> values, int limit) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < values.size() && i < limit; i++) {
            String value = values.get(i);
            out.add(value.length() > 60 ? value.substring(0, 60) + "…" : value);
        }
        if (values.size() > limit) {
            out.add("等共 " + values.size() + " 项");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 工具结果追踪与熔断
    // ------------------------------------------------------------------

    /** 增量扫描新增的工具结果消息，统计失败次数与执行过的工具名。 */
    private void scanToolResults(LoopContext ctx, RunState state) {
        List<ChatMessage> messages = ctx.messages();
        for (int i = state.cursor; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.role() != Role.TOOL) {
                continue;
            }
            String toolName = message.name() == null ? "" : message.name();
            state.executedTools.add(toolName);
            if (PromptTemplates.hasTodoTool(List.of(toolName))) {
                state.todoToolUsed = true;
            }
            if (isError(message)) {
                state.consecutiveErrors++;
                state.totalErrors++;
            } else {
                state.consecutiveErrors = 0;
            }
        }
        state.cursor = messages.size();
    }

    /**
     * 连续工具失败熔断。
     *
     * <p>Loop 无法判断失败原因，能做的只有"提醒模型停下来重新评估"；触发后清零计数，
     * 这样模型再连续失败一轮还会被再次提醒。失败次数也会写进 attributes，便于外部观测。</p>
     */
    private String errorCircuitBreaker(LoopContext ctx, RunState state) {
        if (maxConsecutiveToolErrors <= 0 || state.consecutiveErrors < maxConsecutiveToolErrors) {
            return null;
        }
        int streak = state.consecutiveErrors;
        state.consecutiveErrors = 0;
        put(ctx, "benxin.claude-code.toolErrorStreak", streak);
        ctx.emit("claude-code.tool-error-streak", streak);
        return "连续 " + streak + " 次工具调用失败，请停下来重新评估方案："
                + "先读一遍错误信息，确认参数、路径与前提假设是否正确；"
                + "换一种做法（或先读文件确认现状），不要重复同一条已经失败过的调用。";
    }

    // ------------------------------------------------------------------
    // 上下文压缩
    // ------------------------------------------------------------------

    /**
     * 检查 token 占用，超阈值时把较早的工具结果压缩成占位摘要。
     *
     * <p>为什么不交给 {@code ContextManager}：它只在组装请求时生效，历史本身仍会无限增长
     * （见类注释）。在 Loop 层就地改写历史，才能让长任务的上下文曲线真正收敛。</p>
     */
    private void maybeCompact(LoopContext ctx, RunState state) {
        int window = contextWindow(ctx);
        if (window <= 0) {
            return;
        }
        int estimated = TokenEstimator.DEFAULT.estimate(ctx.messages());
        if (estimated < window * COMPACT_TRIGGER_RATIO) {
            return;
        }
        int compacted = compactToolResults(ctx, COMPACT_KEEP_RECENT_RESULTS);
        if (compacted <= 0) {
            return;
        }
        state.compactions += compacted;
        put(ctx, ATTR_COMPACTIONS, totalCompactions(ctx) + compacted);
        ctx.emit("compaction", Map.of(
                "loop", NAME,
                "compacted", compacted,
                "estimatedTokens", estimated,
                "contextWindow", window));
        log.debug("[claude-code] 上下文压缩：改写 {} 条较早的工具结果（估算 {} tokens / 窗口 {}）",
                compacted, estimated, window);
    }

    /**
     * 把"较早的工具结果"就地改写成"保留开头若干字符 + 压缩标记"的占位摘要。
     *
     * <p>只改工具结果、不动 assistant 的文本与工具调用结构：{@code tool_use} 与 {@code tool_result}
     * 的配对关系必须保持完整，否则 Anthropic 一类协议会直接报错。</p>
     *
     * @param keepRecent 最近保留（不压缩）的工具结果条数
     * @return 实际改写的条数
     */
    private int compactToolResults(LoopContext ctx, int keepRecent) {
        List<ChatMessage> messages = ctx.messages();
        int boundary = compactionBoundary(messages, keepRecent);
        if (boundary <= 0) {
            return 0;
        }
        int compacted = 0;
        for (int i = 0; i < boundary; i++) {
            ChatMessage message = messages.get(i);
            if (message.role() != Role.TOOL || message.text().contains(COMPACT_MARKER)) {
                continue;
            }
            String text = message.text();
            if (text.length() <= COMPACT_KEEP_CHARS) {
                continue;
            }
            String head = text.substring(0, COMPACT_KEEP_CHARS);
            String replaced = head + "\n" + COMPACT_MARKER + " 该结果已压缩，仅保留前 "
                    + COMPACT_KEEP_CHARS + " 字符（原 " + text.length() + " 字符）]…";
            ChatMessage compactedMessage = ChatMessage.toolResult(
                    message.toolCallId(), message.name(), replaced, isError(message));
            try {
                messages.set(i, compactedMessage);
                compacted++;
            } catch (UnsupportedOperationException e) {
                // 自定义 LoopContext 可能返回不可变历史：此时放弃压缩，不影响主流程
                log.debug("[claude-code] 历史不可变，跳过工具结果压缩");
                return compacted;
            }
        }
        return compacted;
    }

    /** 计算可以压缩的区间上界：只压缩"最近 keepRecent 条工具结果"之前的部分。 */
    private static int compactionBoundary(List<ChatMessage> messages, int keepRecent) {
        int keep = Math.max(0, keepRecent);
        int seen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.TOOL) {
                seen++;
                if (seen > keep) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    private int totalCompactions(LoopContext ctx) {
        Object value = attributes(ctx).get(ATTR_COMPACTIONS);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static int contextWindow(LoopContext ctx) {
        try {
            return ctx.model() == null ? 0 : ctx.model().capabilities().maxContextTokens();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 子代理
    // ------------------------------------------------------------------

    /**
     * 派生一个子代理执行独立任务（供 {@code task} 工具经由
     * {@code ToolContext.spawn} → {@code LoopContext.spawnSubAgent} 调用）。
     *
     * <p>{@code doRun} 不会主动派生子代理——是否值得派生由模型决定；这里只负责把子代理的
     * 用量补记到父上下文，并发一个事件出来，方便外部观测子任务的边界。</p>
     *
     * @param ctx           当前 Loop 上下文
     * @param subAgentName  子代理名；为空时用构造器配置的默认名
     * @param prompt        交给子代理的完整自洽指令（子代理看不到父上下文）
     * @return 子代理的执行结果
     * @throws UnsupportedOperationException Agent 未配置子代理能力时抛出（工具应据此返回错误结果）
     */
    public AgentResult spawnTask(LoopContext ctx, String subAgentName, String prompt) {
        String name = subAgentName == null || subAgentName.isBlank() ? this.subAgentName : subAgentName;
        if (!supportsSubAgents(ctx)) {
            throw new UnsupportedOperationException(
                    "该 Agent 未配置子代理能力，无法派生 [" + name + "]；请改用现有工具自行完成任务");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("派生 [" + name + "] 时必须给出完整指令（子代理看不到当前对话）");
        }
        ctx.emit("claude-code.subagent-start", Map.of("subAgent", name, "promptChars", prompt.length()));
        // 只传递明确的父子关系信息，不透传父上下文的全部属性（避免子任务被父任务的状态污染）
        AgentResult result = ctx.spawnSubAgent(name, prompt,
                Map.of("parentAgent", ctx.agentName(), "parentStep", ctx.step()));
        if (result != null) {
            addUsage(ctx, result.usage());
            ctx.emit("claude-code.subagent-end", Map.of(
                    "subAgent", name,
                    "steps", result.steps(),
                    "textChars", result.text() == null ? 0 : result.text().length()));
        }
        return result;
    }

    /** 当前 Agent 是否支持子代理（对自定义 LoopContext 也安全）。 */
    private static boolean supportsSubAgents(LoopContext ctx) {
        try {
            return ctx.supportsSubAgents();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> safeToolNames(LoopContext ctx) {
        try {
            Collection<String> names = ctx.toolNames();
            return names == null ? List.of() : new ArrayList<>(names);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean isError(ChatMessage message) {
        return message.parts().stream()
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .anyMatch(ToolResultPart::error);
    }

    private static Map<String, Object> attributes(LoopContext ctx) {
        Map<String, Object> attributes = ctx.attributes();
        return attributes == null ? Map.of() : attributes;
    }
    private static Object get(LoopContext ctx, String key) {
        return attributes(ctx).get(key);
    }

    /** 写属性；自定义 LoopContext 若给出不可变 map，只记一条 debug 日志，绝不中断主循环。 */
    private void put(LoopContext ctx, String key, Object value) {
        try {
            Map<String, Object> attributes = ctx.attributes();
            if (attributes != null) {
                attributes.put(key, value);
            }
        } catch (UnsupportedOperationException e) {
            log.debug("[claude-code] 属性表不可写，忽略 {} 的写入", key);
        }
    }

    /**
     * 待办条目。
     *
     * @param index      在清单里的序号（从 1 开始，用于提醒里点名"第 N 项"）
     * @param text       条目内容
     * @param done       是否已完成
     * @param inProgress 是否正在进行
     */
    private record TodoItem(int index, String text, boolean done, boolean inProgress) {
    }

    /** 一次运行的临时状态（不放进 attributes，避免并发运行互相干扰）。 */
    private static final class RunState {
        /** 已扫描到的消息下标。 */
        private int cursor;
        /** 连续工具失败次数。 */
        private int consecutiveErrors;
        /** 工具失败总次数。 */
        private int totalErrors;
        /** 已压缩的工具结果条数。 */
        private int compactions;
        /** 待办工具是否被调用过。 */
        private boolean todoToolUsed;
        /** 执行过的工具名。 */
        private final Set<String> executedTools = new LinkedHashSet<>();
    }
}
