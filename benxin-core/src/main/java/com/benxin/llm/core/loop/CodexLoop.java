package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.prompt.PromptTemplates;
import com.benxin.llm.core.prompt.PromptTemplates.PromptContext;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.util.Json;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * codex Loop：把 Codex 的"turn 制"工作方式用 Java 还原。
 *
 * <p>设计来源：Codex 的 turn 结构——先出计划、再做最小改动、最后必须验证；沙箱与审批权限
 * 明确告知模型，越权操作不会被执行。本类的三段式对应关系：</p>
 * <ol>
 *   <li><b>计划阶段</b>：任务不短（用户输入 &gt; 80 字符）时先追加一条 user 消息要计划，
 *       从回复文本（或 {@code update_plan} 一类工具的参数）里解析出步骤，写进
 *       {@code benxin.codex.plan}。解析失败就跳过计划直接执行——计划阶段绝不能成为卡点。</li>
 *   <li><b>执行阶段</b>：{@link AbstractAgentLoop#runToolCallingLoop} 的标准骨架，
 *       每步注入进度提醒与"命令失败先读错误"的提醒。</li>
 *   <li><b>验证阶段</b>：模型准备收尾时，若全程没有任何命令类工具成功执行过，
 *       再给它一次机会去验证（最多干预一次），对应 Codex "先验证再汇报"的硬约束。</li>
 * </ol>
 *
 * <p>关键取舍：</p>
 * <ol>
 *   <li><b>权限必须如实告知</b>：沙箱是否允许写、是否允许执行命令、当前审批策略，全部写进首轮注入的
 *       环境信息。模型只有知道自己的边界才不会反复撞墙——"没写权限却一直尝试改文件"是本类要消灭的头号
 *       浪费。未开启写权限时还会明确要求"不要尝试直接修改文件，改为输出将要执行的补丁"。</li>
 *   <li><b>进度计数是代理指标</b>：Codex 是"模型自己勾选计划项"，而 {@code update_plan} 的具体
 *       参数结构由工具实现决定，Loop 无法可靠地判定"第 3 步完成了"。因此
 *       {@code benxin.codex.completedSteps} 的取值策略是：若计划类工具的参数里明确带了完成状态，
 *       就用它的计数（权威）；否则退化为"已结束的工作步数"，并在提醒里用"约完成"措辞，避免误导模型。</li>
 *   <li><b>验证干预只做一次</b>：用 attributes 标记防止无限循环。若工具集里根本没有命令类工具，
 *       不会强行要求验证（那只会让模型卡死），而是在 attributes 里如实记下"无法验证及原因"。</li>
 *   <li><b>裸环境可用</b>：没有任何工具、模型不支持工具调用（只会回文本）时，本 Loop 只会退化成
 *       "要计划 → 收到回答 → 提示验证一次 → 收尾"，不会抛异常。</li>
 * </ol>
 */
@LlmLoop("codex")
public class CodexLoop extends AbstractAgentLoop {

    /** Loop 名。 */
    public static final String NAME = "codex";

    private static final String ATTR_INITIALIZED = "benxin.codex.initialized";
    private static final String ATTR_PLAN = "benxin.codex.plan";
    private static final String ATTR_COMPLETED_STEPS = "benxin.codex.completedSteps";
    private static final String ATTR_PLAN_REVISIONS = "benxin.codex.planRevisions";
    private static final String ATTR_VERIFIED = "benxin.codex.verified";
    private static final String ATTR_VERIFY_NUDGE = "benxin.codex.verifyReminderSent";
    private static final String ATTR_VERIFICATION_NOTE = "benxin.codex.verificationNote";
    private static final String ATTR_WRITE_ENABLED = "benxin.codex.sandboxWriteEnabled";
    private static final String ATTR_EXEC_ENABLED = "benxin.codex.execEnabled";
    private static final String ATTR_APPROVAL = "benxin.codex.approvalPolicy";
    private static final String ATTR_FALLBACK_PROMPT = "benxin.codex.fallbackPromptInjected";
    private static final String ATTR_LAST_TOOL_ERROR = "benxin.codex.lastToolError";
    private static final String ATTR_STEPS = "benxin.codex.steps";

    /** 超过这个长度的用户输入才值得先做计划（极短任务做计划纯属浪费一步）。 */
    private static final int PLAN_THRESHOLD_CHARS = 80;

    /** 计划最多解析出多少步（防止模型输出一屏"步骤"）。 */
    private static final int MAX_PLAN_STEPS = 20;

    /** 计划类工具的判定关键词（工具名包含其一即视为计划工具）。 */
    private static final List<String> PLAN_TOOL_KEYWORDS = List.of("plan");

    /** 编号步骤：{@code 1. xxx} / {@code 1) xxx} / {@code 步骤 1：xxx}。 */
    private static final Pattern NUMBERED_STEP = Pattern.compile(
            "(?m)^\\s*(?:步骤|step)?\\s*(\\d{1,2})\\s*[.、)）:：]\\s*(\\S.*)$");

    /** 勾选式步骤：{@code - [ ] xxx} / {@code * [x] xxx}。 */
    private static final Pattern CHECKBOX_STEP = Pattern.compile(
            "(?m)^\\s*[-*+]\\s*\\[\\s*[ xX]?\\s*]\\s*(\\S.*)$");

    /** 步骤文本里的验证标记。 */
    private static final Pattern VERIFY_MARKER = Pattern.compile(
            "(?:验证方式|验证|如何验证|verify|verification|检查方式)\\s*[:：]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    /** 系统提示词兜底注入时的前缀；判断"用户任务有多长"时要跳过它。 */
    private static final String FALLBACK_MARKER = "【本心内置系统提示｜codex】";

    /** 首轮计划请求。 */
    private static final String PLAN_REQUEST = """
            在动手之前，先用一份简短计划回应我——只输出计划文本，不要调用任何工具、不要写代码。
            格式：
            1. <这一步要做的具体动作> ｜ 验证：<用什么命令或方法确认这一步做对了>
            2. ...
            要求：3-6 步；每一步都必须是可独立验证的最小动作；如果任务其实很简单，只回一行"无需计划"。""";

    /** 验证干预（放进 user 消息，只发一次）。 */
    private static final String VERIFY_NUDGE = """
            在给出结论之前，请先用命令验证一遍：运行编译或测试，或者用一条最小命令复现你修改过的行为，
            然后把你实际执行的命令与真实输出写进回答里。没有跑过的结论请明确标注为"未验证"。""";

    /** 是否允许沙箱写文件。 */
    private final boolean sandboxWriteEnabled;

    /** 是否允许执行命令。 */
    private final boolean execEnabled;

    /** 当前审批策略。 */
    private final ApprovalPolicy approvalPolicy;

    /** 计划最多允许修订的次数。 */
    private final int maxPlanRevisions;

    public CodexLoop() {
        this(false, false, ApprovalPolicy.ON_REQUEST, 3);
    }

    public CodexLoop(boolean sandboxWriteEnabled, boolean execEnabled) {
        this(sandboxWriteEnabled, execEnabled, ApprovalPolicy.ON_REQUEST, 3);
    }

    /**
     * @param sandboxWriteEnabled 沙箱是否允许写文件（默认 false：只读起步更安全）
     * @param execEnabled         是否允许执行命令（默认 false）
     * @param approvalPolicy      审批策略
     * @param maxPlanRevisions    计划最多修订几次（&lt;=0 视为默认 3）
     */
    public CodexLoop(boolean sandboxWriteEnabled, boolean execEnabled, ApprovalPolicy approvalPolicy,
                     int maxPlanRevisions) {
        this.sandboxWriteEnabled = sandboxWriteEnabled;
        this.execEnabled = execEnabled;
        this.approvalPolicy = approvalPolicy == null ? ApprovalPolicy.ON_REQUEST : approvalPolicy;
        this.maxPlanRevisions = maxPlanRevisions <= 0 ? 3 : maxPlanRevisions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Codex 风格 turn 循环：先计划、再最小改动、最后必须验证，权限边界如实告知模型";
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        RunState state = new RunState();

        // 「任务是不是极短」必须在注入任何东西之前判定：本 Loop 会往历史里追加环境提示与提醒，
        // 它们同样是 user 消息，注入之后再量长度就会把提示词当成用户任务（计划阶段被误触发）。
        boolean longTask = isLongTask(ctx);

        // 1) 首轮注入权限与环境（模型必须知道自己的边界）
        if (!Boolean.TRUE.equals(get(ctx, ATTR_INITIALIZED))) {
            injectEnvironment(ctx);
        }
        // 2) 系统提示词兜底（外部已提供则完全不动）
        applyFallbackSystemPrompt(ctx);

        // 3) 计划阶段
        List<Map<String, Object>> plan = planPhase(ctx, state, longTask);
        // 计划阶段的消息不计入执行阶段的增量扫描（否则计划工具会被重复计成"修订"）
        state.cursor = ctx.messages().size();

        // 4) 执行阶段
        LoopResult result = runToolCallingLoop(ctx, current -> stepHook(current, state));

        // 5) 验证阶段：模型准备收尾但从未成功跑过命令时，再给一次机会（仅一次）
        if (shouldNudgeVerification(ctx, state, result)) {
            state.verificationNudged = true;
            put(ctx, ATTR_VERIFY_NUDGE, Boolean.TRUE);
            remind(ctx, VERIFY_NUDGE);
            ctx.emit("codex.verify-reminder", "本次运行尚未成功执行过命令类工具，已要求模型先验证再收尾");
            log.debug("[codex] 未检测到成功的命令执行，已注入验证提醒");
            // 重新进入主循环：模型的下一段输出才会被当作结论
            result = runToolCallingLoop(ctx, current -> stepHook(current, state));
        }

        // 6) 收尾统计
        boolean verified = state.commandSucceeded;
        put(ctx, ATTR_VERIFIED, verified);
        put(ctx, ATTR_STEPS, ctx.step());
        if (!verified) {
            put(ctx, ATTR_VERIFICATION_NOTE, verificationNote(ctx, state, plan));
        }
        ctx.emit("codex.done", Map.of(
                "steps", ctx.step(),
                "verified", verified,
                "planSteps", plan.size(),
                "completedSteps", completedSteps(ctx),
                "planRevisions", state.planRevisions,
                "executedTools", List.copyOf(state.executedTools)));
        // LoopResult 构造时就把 attributes 拷成了快照，而 verified 等统计是主循环返回后才写的。
        // 对标准上下文重新封装一次，保证 LoopResult.attributes() 与 ctx.attributes() 一致。
        return ctx instanceof DefaultLoopContext ? buildResult(ctx, result.text()) : result;
    }

    /**
     * 每步开始前的钩子：进度提醒 + 失败提醒 + 计划修订熔断。
     *
     * <p>提醒合并成一条 user 消息发送，原因同 claude-code（协议兼容 + 遵从度）。</p>
     */
    private void stepHook(LoopContext ctx, RunState state) {
        scanToolResults(ctx, state);

        List<String> reminders = new ArrayList<>();
        String failure = failureReminder(ctx, state);
        if (failure != null) {
            reminders.add(failure);
        }
        String progress = progressReminder(ctx, state);
        if (progress != null) {
            reminders.add(progress);
        }
        String revision = planRevisionReminder(ctx, state);
        if (revision != null) {
            reminders.add(revision);
        }
        if (!reminders.isEmpty()) {
            remind(ctx, String.join("\n", reminders));
            ctx.emit("codex.reminder", String.join(" | ", reminders));
        }
    }

    // ------------------------------------------------------------------
    // 首轮环境注入
    // ------------------------------------------------------------------

    /**
     * 把沙箱、执行、审批三项能力写进 attributes 并发事件。
     *
     * <p>同时会把说明写进提示词兜底（见 {@link #applyFallbackSystemPrompt}）：模型只有知道
     * "写权限未开启"，才会改为输出补丁而不是反复尝试改文件。</p>
     */
    private void injectEnvironment(LoopContext ctx) {
        List<String> tools = safeToolNames(ctx);
        put(ctx, ATTR_INITIALIZED, Boolean.TRUE);
        put(ctx, ATTR_WRITE_ENABLED, sandboxWriteEnabled);
        put(ctx, ATTR_EXEC_ENABLED, execEnabled);
        put(ctx, ATTR_APPROVAL, approvalPolicy.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cwd", System.getProperty("user.dir", "."));
        payload.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        payload.put("tools", tools);
        payload.put("sandboxWriteEnabled", sandboxWriteEnabled);
        payload.put("execEnabled", execEnabled);
        payload.put("approvalPolicy", approvalPolicy.name());
        ctx.emit("codex.init", payload);
        log.debug("[codex] 注入权限：写={} 执行={} 审批={} 工具={} 个",
                sandboxWriteEnabled, execEnabled, approvalPolicy, tools.size());
    }

    /** 系统提示词兜底：权限用构造器里的真实配置渲染，不用工具清单推断。 */
    private void applyFallbackSystemPrompt(LoopContext ctx) {
        String existing = ctx.systemPrompt();
        if (existing != null && !existing.isBlank()) {
            return;
        }
        if (Boolean.TRUE.equals(get(ctx, ATTR_FALLBACK_PROMPT))) {
            return;
        }
        String fallback = PromptTemplates.codex(promptContext(ctx), sandboxWriteEnabled, execEnabled,
                approvalPolicy);
        ctx.append(ChatMessage.user(FALLBACK_MARKER + "\n" + fallback));
        put(ctx, ATTR_FALLBACK_PROMPT, Boolean.TRUE);
        ctx.emit("codex.system-prompt-fallback", "ctx.systemPrompt() 为空，已注入内置提示词");
        log.debug("[codex] 系统提示词为空，已注入内置兜底提示词（{} 字符）", fallback.length());
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
    // 计划阶段
    // ------------------------------------------------------------------

    /**
     * 计划阶段：要求模型先给出"步骤 + 验证方式"，解析后写进 {@code benxin.codex.plan}。
     *
     * <p>解析失败不阻塞：最多尝试 {@link #maxPlanRevisions} 次，仍拿不到结构化的计划就直接进入执行阶段
     * （模型自己的计划文本已经留在历史里，人读得懂就够了）。</p>
     *
     * @return 解析出的计划步骤；无计划时返回空列表
     */
    private List<Map<String, Object>> planPhase(LoopContext ctx, RunState state, boolean longTask) {
        if (!longTask) {
            log.debug("[codex] 用户输入较短，跳过计划阶段");
            return List.of();
        }
        List<Map<String, Object>> steps = List.of();
        for (int attempt = 1; attempt <= maxPlanRevisions; attempt++) {
            if (outOfBudget(ctx)) {
                log.debug("[codex] 步数预算已用完，跳过计划阶段");
                return List.of();
            }
            remind(ctx, attempt == 1 ? planRequest(ctx)
                    : "上一轮回复里没有解析出编号计划。请只输出编号步骤（每步一行，格式为「1. 动作 ｜ 验证：方法」），"
                            + "不要调用工具。");
            ChatResponse response = ctx.callModel();
            ChatMessage message = response == null ? null : response.message();
            if (message == null) {
                return List.of();
            }
            // 非标准 LoopContext 不会自动把 assistant 消息写进历史：这里补一次，
            // 否则后面执行工具会产生"孤儿 tool_result"（Anthropic 一类协议会直接报错）
            appendIfMissing(ctx, message);

            String text = message.text();
            if (text != null && (text.contains("无需计划") || text.contains("不需要计划"))) {
                ctx.emit("codex.plan-skipped", "模型判断任务简单，无需计划");
                return List.of();
            }
            List<ToolUsePart> toolUses = message.toolUses();
            if (!toolUses.isEmpty()) {
                List<ChatMessage> results = ctx.executeTools(toolUses);
                for (ChatMessage result : results) {
                    appendIfMissing(ctx, result);
                }
            }
            steps = parsePlan(text, toolUses);
            if (!steps.isEmpty()) {
                break;
            }
        }
        if (steps.isEmpty()) {
            log.debug("[codex] 未能解析出结构化计划，直接进入执行阶段");
            return List.of();
        }
        put(ctx, ATTR_PLAN, new ArrayList<>(steps));
        put(ctx, ATTR_COMPLETED_STEPS, 0);
        ctx.emit("codex.plan", steps);
        log.debug("[codex] 计划阶段完成，共 {} 步", steps.size());
        return steps;
    }

    /**
     * 首次计划请求：有 {@code update_plan} 一类计划工具时，请模型同时把计划登记进去。
     *
     * <p>工具名按注册顺序实际探测，绝不写死：这就是"提示词只提真实存在的工具"。</p>
     */
    private String planRequest(LoopContext ctx) {
        String planTool = PromptTemplates.firstMatchingTool(safeToolNames(ctx), PLAN_TOOL_KEYWORDS);
        if (planTool == null) {
            return PLAN_REQUEST;
        }
        return PLAN_REQUEST + "\n如果你决定用 " + planTool + " 工具登记计划，请把同样内容的计划也直接写在回复里。";
    }

    /** 任务是否"不算极短"：看最后一条真实 user 消息的长度。
     *
     * <p>会跳过本 Loop 自己注入的兜底系统提示（见 {@link #FALLBACK_MARKER}），
     * 否则"提示词很长"会被误判成"用户任务很长"。</p>
     */
    private boolean isLongTask(LoopContext ctx) {
        String text = lastUserText(ctx);
        return text != null && text.length() > PLAN_THRESHOLD_CHARS;
    }

    private static String lastUserText(LoopContext ctx) {
        List<ChatMessage> messages = ctx.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() != Role.USER) {
                continue;
            }
            String text = message.text();
            if (text.isBlank() || text.startsWith(FALLBACK_MARKER)) {
                continue;
            }
            return text;
        }
        return null;
    }

    /**
     * 从模型回复（文本 + 计划类工具参数）里解析步骤。
     *
     * <p>先看工具参数（结构化、最可靠），再看文本里的编号/勾选行。</p>
     */
    private List<Map<String, Object>> parsePlan(String text, List<ToolUsePart> toolUses) {
        List<Map<String, Object>> fromTools = parsePlanFromTools(toolUses);
        if (!fromTools.isEmpty()) {
            return fromTools;
        }
        return parsePlanFromText(text);
    }

    private List<Map<String, Object>> parsePlanFromTools(List<ToolUsePart> toolUses) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (ToolUsePart toolUse : toolUses) {
            if (!isPlanTool(toolUse.name())) {
                continue;
            }
            JsonNode root = Json.parseQuietly(toolUse.argumentsJson());
            if (root == null || root.isNull()) {
                continue;
            }
            for (String field : List.of("plan", "steps", "items", "todos", "tasks")) {
                JsonNode array = root.path(field);
                if (!array.isArray()) {
                    continue;
                }
                for (JsonNode node : array) {
                    if (steps.size() >= MAX_PLAN_STEPS) {
                        break;
                    }
                    String step = node.isTextual() ? node.asText()
                            : firstNonBlank(node, "step", "content", "text", "description", "title", "task");
                    if (step == null || step.isBlank()) {
                        continue;
                    }
                    String verify = node.isObject()
                            ? firstNonBlank(node, "verify", "verification", "check", "validation")
                            : null;
                    steps.add(stepEntry(steps.size() + 1, step, verify == null ? "" : verify));
                }
            }
            if (!steps.isEmpty()) {
                return steps;
            }
        }
        return steps;
    }

    private List<Map<String, Object>> parsePlanFromText(String text) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return steps;
        }
        collectSteps(text, NUMBERED_STEP, steps);
        if (steps.isEmpty()) {
            collectSteps(text, CHECKBOX_STEP, steps);
        }
        return steps;
    }

    private void collectSteps(String text, Pattern pattern, List<Map<String, Object>> steps) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && steps.size() < MAX_PLAN_STEPS) {
            String raw = matcher.group(matcher.groupCount()).strip();
            if (raw.isEmpty() || raw.length() > 400) {
                continue;
            }
            String verify = "";
            Matcher verifyMatcher = VERIFY_MARKER.matcher(raw);
            String step = raw;
            if (verifyMatcher.find()) {
                verify = verifyMatcher.group(1).strip();
                step = raw.substring(0, verifyMatcher.start()).strip();
                step = step.replaceAll("[|｜—\\-–]+$", "").strip();
            }
            if (!step.isEmpty()) {
                steps.add(stepEntry(steps.size() + 1, step, verify));
            }
        }
    }

    private static Map<String, Object> stepEntry(int index, String step, String verify) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("index", index);
        entry.put("step", step.length() > 400 ? step.substring(0, 400) : step);
        entry.put("verify", verify.length() > 200 ? verify.substring(0, 200) : verify);
        return entry;
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 执行阶段的提醒
    // ------------------------------------------------------------------

    /** 上一步有工具失败时提醒"先读错误再改"，每个失败批次只提醒一次。 */
    private String failureReminder(LoopContext ctx, RunState state) {
        Object recorded = get(ctx, ATTR_LAST_TOOL_ERROR);
        if (!(recorded instanceof String failure) || failure.isBlank()) {
            return null;
        }
        put(ctx, ATTR_LAST_TOOL_ERROR, "");
        return "上一步有工具失败：" + failure + "。请先完整读一遍错误信息再修改，"
                + "不要重复执行同一条已经失败的命令。";
    }

    /** 计划存在且模型确实推进了进度时，注入一条简短进度提醒。 */
    private String progressReminder(LoopContext ctx, RunState state) {
        List<Map<String, Object>> plan = plan(ctx);
        if (plan.isEmpty()) {
            return null;
        }
        int completed = completedSteps(ctx);
        // 有进展时立即提醒；否则每 3 步轻推一次（避免每步都灌一段提醒把上下文撑满）
        boolean advanced = completed > state.lastRemindedCompleted && completed > 0;
        if (!advanced && ctx.step() % 3 != 0) {
            return null;
        }
        state.lastRemindedCompleted = completed;
        int next = Math.min(completed, plan.size() - 1);
        String nextStep = String.valueOf(plan.get(next).getOrDefault("step", ""));
        String verify = String.valueOf(plan.get(next).getOrDefault("verify", ""));
        return "计划进度：约完成 " + completed + "/" + plan.size() + " 项。当前推进「" + nextStep + "」"
                + (verify.isBlank() ? "" : "，完成标准：" + verify)
                + "。不要跳过验证，也不要在未验证时宣布完成。";
    }

    /** 计划被反复修订时提醒"别再改计划了，动手"。 */
    private String planRevisionReminder(LoopContext ctx, RunState state) {
        if (state.planRevisions <= maxPlanRevisions || state.planRevisionReminded) {
            return null;
        }
        state.planRevisionReminded = true;
        return "计划已经修订 " + state.planRevisions + " 次，超过上限（" + maxPlanRevisions
                + "）。请停止继续调整计划，按当前计划直接执行最小改动，然后用命令验证。";
    }

    /** 增量扫描新增的工具结果：记录失败、命令执行情况、计划类工具的修订次数。 */
    private void scanToolResults(LoopContext ctx, RunState state) {
        List<ChatMessage> messages = ctx.messages();
        for (int i = state.cursor; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.role() == Role.ASSISTANT) {
                for (ToolUsePart toolUse : message.toolUses()) {
                    state.executedTools.add(toolUse.name());
                    if (isPlanTool(toolUse.name())) {
                        state.planRevisions++;
                        put(ctx, ATTR_PLAN_REVISIONS, state.planRevisions);
                    }
                }
                continue;
            }
            if (message.role() != Role.TOOL) {
                continue;
            }
            String toolName = message.name() == null ? "" : message.name();
            if (isError(message)) {
                String error = firstLine(message.text(), 200);
                put(ctx, ATTR_LAST_TOOL_ERROR, toolName + "：" + error);
                state.totalErrors++;
                continue;
            }
            if (isCommandTool(toolName)) {
                state.commandSucceeded = true;
                state.successfulSteps++;
                advanceCompletedSteps(ctx, state);
            } else if (!isPlanTool(toolName)) {
                state.successfulSteps++;
                advanceCompletedSteps(ctx, state);
            }
        }
        state.cursor = messages.size();
    }

    /**
     * 推进"已完成步数"。
     *
     * <p>代理指标说明见类注释：没有可靠的语义信号时，用"结束的工作步数"近似计划进度，并封顶在计划长度。</p>
     */
    private void advanceCompletedSteps(LoopContext ctx, RunState state) {
        List<Map<String, Object>> plan = plan(ctx);
        if (plan.isEmpty()) {
            return;
        }
        int next = Math.min(state.successfulSteps, plan.size());
        if (next > completedSteps(ctx)) {
            put(ctx, ATTR_COMPLETED_STEPS, next);
        }
    }

    private int completedSteps(LoopContext ctx) {
        Object value = get(ctx, ATTR_COMPLETED_STEPS);
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> plan(LoopContext ctx) {
        Object value = get(ctx, ATTR_PLAN);
        if (value instanceof List<?> list) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    steps.add((Map<String, Object>) map);
                }
            }
            return steps;
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 验证阶段
    // ------------------------------------------------------------------

    /**
     * 是否需要在收尾前"逼一次验证"。
     *
     * <p>这是 Codex "先验证再汇报"的还原：模型很容易在没跑过任何命令的情况下宣布完成。
     * 三个前提缺一不可——还没干预过、工具集里确实有命令类工具、且没有任何命令成功执行过。
     * 第三条里的"工具集里确实有"尤其重要：没有命令工具时强行要求验证只会让模型无所适从。</p>
     */
    private boolean shouldNudgeVerification(LoopContext ctx, RunState state, LoopResult result) {
        if (state.verificationNudged || state.commandSucceeded) {
            return false;
        }
        if (result != null && result.maxStepsReached()) {
            return false;
        }
        if (outOfBudget(ctx)) {
            return false;
        }
        if (!hasCommandTooling(ctx)) {
            return false;
        }
        return true;
    }

    private boolean hasCommandTooling(LoopContext ctx) {
        return PromptTemplates.hasCommandTool(safeToolNames(ctx));
    }

    private static boolean isCommandTool(String toolName) {
        return PromptTemplates.hasCommandTool(List.of(toolName == null ? "" : toolName));
    }

    private static boolean isPlanTool(String toolName) {
        return PromptTemplates.firstMatchingTool(
                List.of(toolName == null ? "" : toolName), PLAN_TOOL_KEYWORDS) != null;
    }

    /** 未验证时的原因说明，写进 attributes 供外部观测（绝不假装已验证）。 */
    private String verificationNote(LoopContext ctx, RunState state, List<Map<String, Object>> plan) {
        if (!hasCommandTooling(ctx)) {
            return "工具集中没有命令类工具（名字含 bash/shell/exec 等），本次运行无法通过命令验证";
        }
        if (state.totalErrors > 0) {
            return "命令类工具曾执行但未成功（累计失败 " + state.totalErrors + " 次），结论未经命令验证";
        }
        return "模型未执行任何命令类工具，结论未经命令验证"
                + (plan.isEmpty() ? "" : "（计划 " + plan.size() + " 步）");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 非标准 LoopContext 不会自动追加 assistant 消息时补一次，保证 tool_use / tool_result 配对完整。 */
    private static void appendIfMissing(LoopContext ctx, ChatMessage message) {
        if (message == null) {
            return;
        }
        List<ChatMessage> messages = ctx.messages();
        if (!messages.isEmpty() && messages.get(messages.size() - 1).equals(message)) {
            return;
        }
        ctx.append(message);
    }

    private static String firstLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int end = text.indexOf('\n');
        String line = (end < 0 ? text : text.substring(0, end)).strip();
        return line.length() > maxChars ? line.substring(0, maxChars) + "…" : line;
    }

    private static boolean isError(ChatMessage message) {
        return message.parts().stream()
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .anyMatch(ToolResultPart::error);
    }

    private static boolean supportsSubAgents(LoopContext ctx) {
        try {
            return ctx.supportsSubAgents();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> safeToolNames(LoopContext ctx) {
        try {
            Set<String> names = ctx.toolNames() == null ? Set.of() : new LinkedHashSet<>(ctx.toolNames());
            return new ArrayList<>(names);
        } catch (RuntimeException e) {
            return List.of();
        }
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
            log.debug("[codex] 属性表不可写，忽略 {} 的写入", key);
        }
    }

    /** 一次运行的临时状态（不放进 attributes，避免并发运行互相干扰）。 */
    private static final class RunState {
        /** 已扫描到的消息下标（增量扫描用）。 */
        private int cursor;
        /** 工具失败总次数。 */
        private int totalErrors;
        /** 有成功工具结果的工作步数（计划进度的代理指标）。 */
        private int successfulSteps;
        /** 计划被修订（计划类工具被调用）的次数。 */
        private int planRevisions;
        /** 上一次进度提醒时的已完成步数。 */
        private int lastRemindedCompleted = -1;
        /** 是否已提醒过"别再改计划"。 */
        private boolean planRevisionReminded;
        /** 是否已经做过一次验证干预。 */
        private boolean verificationNudged;
        /** 是否成功执行过命令类工具。 */
        private boolean commandSucceeded;
        /** 执行过的工具名。 */
        private final Set<String> executedTools = new LinkedHashSet<>();
    }
}
