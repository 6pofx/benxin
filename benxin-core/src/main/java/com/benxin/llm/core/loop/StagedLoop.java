package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.util.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>结构化编排模式</b>：把"规划 → 执行 → 校验 → 修复 → 汇总"固化成一条受控的阶段流水线。
 *
 * <p>与 {@code plan-execute} 的差别只有一条，但很关键：<b>它多了一道验收闸门</b>。
 * plan-execute 在最后一步走完就直接汇总，于是"步骤都跑了"和"任务真的完成了"被当成一回事 ——
 * 而这两件事经常不是一回事：模型可能漏掉一步、可能只做了一半、也可能答非所问。
 * 本 Loop 在汇总之前强制做一次独立校验，未通过则带着具体问题回到修复阶段，
 * 直到通过、或修复轮次用尽。</p>
 *
 * <p>阶段与流转（写死在代码里，不依赖模型自觉）：</p>
 *
 * <pre>
 *   PLAN ──► EXECUTE ──► VERIFY ──通过──► SYNTHESIZE ──► 结束
 *                          │  ▲
 *                        不通过 │
 *                          ▼  │
 *                        REPAIR ┘（最多 maxRepairRounds 轮）
 * </pre>
 *
 * <p>与 {@code WorkflowLoop} 的分工：那个是"<b>图由外部定义</b>"，适合流程固定、
 * 需要可审计可复现的场景；这个是"<b>流程由代码固化</b>"，适合任务形态多变、
 * 但希望稳定获得"先做后验"收益的场景。这是工作流模式的两条路线，不是同一个东西的两种写法。</p>
 *
 * <p><b>一处刻意的容错</b>：与 {@code reflexion} 一致，校验结论解析不出来时判定为通过。
 * 理由是"无结论即重试"只会把预算烧在反复验收上，而此时的产出通常已经可用；
 * 反过来，把解析失败当成不通过，则会让一个格式跑偏的模型回复拖垮整次调用。</p>
 */
@LlmLoop(StagedLoop.NAME)
public class StagedLoop extends AbstractAgentLoop {

    /** Loop 名，供 {@code @LlmAgent(loop = "staged")} 引用。 */
    public static final String NAME = "staged";

    /** 当前阶段写回该属性，供监听器 / UI 展示进度。 */
    public static final String STAGE_ATTRIBUTE = "benxin.staged.stage";

    /** 规划结果（步骤列表）。 */
    public static final String PLAN_ATTRIBUTE = "benxin.staged.plan";

    /** 最近一次校验结论：{@code pass} / {@code fail}。 */
    public static final String VERDICT_ATTRIBUTE = "benxin.staged.verdict";

    /** 已进行的修复轮次。 */
    public static final String REPAIR_ROUNDS_ATTRIBUTE = "benxin.staged.repair-rounds";

    /** 即使模型一直在调工具，每经过这么多轮也回显一次进度。 */
    private static final int PROGRESS_EVERY_ROUNDS = 3;

    /** 事件负载里文本的截断长度。 */
    private static final int EVENT_TEXT_LIMIT = 512;

    /** 先判"不通过"再判"通过"：否则「结论：不通过」会被"通过"两个字误判成通过。 */
    private static final Pattern FAIL_VERDICT = Pattern.compile(
            "(?i)(结论\\s*[:：]\\s*(?:不通过|未通过|不达标|未达标|不合格)|\\bFAIL(?:ED)?\\b|不合格)");

    private static final Pattern PASS_VERDICT = Pattern.compile(
            "(?i)(结论\\s*[:：]\\s*(?:通过|达标|合格)|\\bPASS(?:ED)?\\b)");

    private static final String PLANNING_PROMPT = """
            请先针对上面的任务制定一份可执行的编号计划。现在不要开始执行，也不要调用任何工具。

            输出要求：
            1. 只输出计划本身，一行一个步骤，使用 "1. 步骤描述" 的格式；
            2. 每一步都要具体到可以独立执行，建议 2 到 8 步；
            3. 不要输出前言、解释或结尾总结。

            我会在你给出计划后逐步要求你执行，并在最后做一次独立验收。
            """;

    private static final String VERIFY_PROMPT = """
            计划的全部步骤都已执行完毕。现在请你转换角色：作为验收者，对照最开始的任务要求，
            检查目前的产出是否真的达标。请不要客气，也不要为了让流程显得顺利完成而给出通过的结论。

            输出格式（严格遵守）：
            第一行只写「结论：通过」或「结论：不通过」；
            若结论为不通过，从第二行开始逐条列出必须修正的问题，每条一行，具体到可以直接执行。
            """;

    private static final String SYNTHESIS_PROMPT = """
            请综合前面各步骤的结果与验收结论，直接给出面向用户的最终答案。
            不要再调用工具，除非确实还缺少关键信息。
            """;

    private final boolean verifyEnabled;
    private final int maxRepairRounds;

    /** 默认：开启校验，最多修复 1 轮。 */
    public StagedLoop() {
        this(true, 1);
    }

    /**
     * @param verifyEnabled   是否启用校验闸门；关闭后退化为"带汇总的 plan-execute"
     * @param maxRepairRounds 校验不通过时最多修复几轮（0 表示只验一次、不修）
     */
    public StagedLoop(boolean verifyEnabled, int maxRepairRounds) {
        this.verifyEnabled = verifyEnabled;
        this.maxRepairRounds = Math.max(0, maxRepairRounds);
    }

    /** 阶段枚举。 */
    public enum Stage {
        PLAN, EXECUTE, VERIFY, REPAIR, SYNTHESIZE;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "分阶段编排：规划 → 执行 → 校验 → 修复 → 汇总，"
                + (verifyEnabled ? "汇总前强制验收（最多修复 " + maxRepairRounds + " 轮）" : "已关闭验收闸门");
    }

    // ------------------------------------------------------------------

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        if (outOfBudget(ctx)) {
            return finishTruncated(ctx);
        }

        // ---------- PLAN ----------
        enter(ctx, Stage.PLAN);
        List<String> steps = planning(ctx);
        if (steps.isEmpty()) {
            log.warn("[staged] 规划阶段没有产出可解析的编号计划，降级为标准的 tool-calling 循环");
            return runToolCallingLoop(ctx);
        }
        ctx.attributes().put(PLAN_ATTRIBUTE, List.copyOf(steps));
        ctx.emit("staged.plan", List.copyOf(steps));
        log.debug("[staged] 计划解析出 {} 步", steps.size());

        // ---------- EXECUTE ----------
        enter(ctx, Stage.EXECUTE);
        StageResult execution = execute(ctx, steps);
        if (execution.status() == Status.DIRECT) {
            return buildResult(ctx, execution.text());
        }
        if (execution.status() == Status.TRUNCATED) {
            return finishTruncated(ctx);
        }

        // ---------- VERIFY ⇄ REPAIR ----------
        if (verifyEnabled) {
            int repairs = 0;
            while (true) {
                if (outOfBudget(ctx)) {
                    return finishTruncated(ctx);
                }
                enter(ctx, Stage.VERIFY);
                Verdict verdict = verify(ctx);
                if (outOfBudget(ctx)) {
                    // 校验本身耗尽了预算：不把这次结论当成真的结论记录，直接如实收尾
                    return finishTruncated(ctx);
                }
                ctx.attributes().put(VERDICT_ATTRIBUTE, verdict.passed() ? "pass" : "fail");
                ctx.emit("staged.verdict", event("passed", verdict.passed(),
                        "detail", abbreviate(verdict.detail())));

                if (verdict.passed()) {
                    break;
                }
                if (repairs >= maxRepairRounds) {
                    log.info("[staged] 修复轮次已用尽（{} 轮），带着未解决的问题收尾", repairs);
                    break;
                }
                repairs++;
                ctx.attributes().put(REPAIR_ROUNDS_ATTRIBUTE, repairs);
                enter(ctx, Stage.REPAIR);
                StageResult repaired = repair(ctx, verdict);
                if (repaired.status() == Status.TRUNCATED) {
                    return finishTruncated(ctx);
                }
                if (repaired.status() == Status.DIRECT) {
                    return buildResult(ctx, repaired.text());
                }
            }
        }

        // ---------- SYNTHESIZE ----------
        if (outOfBudget(ctx)) {
            return finishTruncated(ctx);
        }
        enter(ctx, Stage.SYNTHESIZE);
        return synthesize(ctx);
    }

    // ------------------------------------------------------------------
    // 各阶段
    // ------------------------------------------------------------------

    private List<String> planning(LoopContext ctx) {
        remind(ctx, PLANNING_PROMPT);
        ChatResponse response = ctx.callModel();
        ChatMessage message = response.message();
        if (message == null) {
            return List.of();
        }
        // 规划阶段本不该调工具，但模型未必听话：补执行掉，否则历史里会留下断裂的 tool_use
        if (!message.toolUses().isEmpty()) {
            log.debug("[staged] 规划阶段模型请求了 {} 个工具调用，补执行以保持历史完整",
                    message.toolUses().size());
            ctx.executeTools(message.toolUses()).forEach(ctx::append);
        }
        return PlanParser.parse(message.text());
    }

    /**
     * 执行阶段：逐条推进计划。
     *
     * <p>沿用 plan-execute 那条已被验证过的信号 —— <b>"模型这一轮不再请求工具"即"当前这一步做完了"</b>。
     * 这是唯一不依赖模型自报进度的可观测信号。</p>
     */
    private StageResult execute(LoopContext ctx, List<String> steps) {
        int total = steps.size();
        int done = 0;
        int round = 0;
        remind(ctx, executionPrompt(steps));

        while (done < total) {
            if (outOfBudget(ctx)) {
                return StageResult.truncated();
            }
            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return StageResult.completed();
            }
            round++;

            List<ToolUsePart> toolUses = message.toolUses();
            if (toolUses.isEmpty()) {
                done = Math.min(done + 1, total);
                ctx.emit("staged.step", event("done", done, "total", total));
                if (done >= total) {
                    return StageResult.completed();
                }
                remind(ctx, progressPrompt(steps, done));
                continue;
            }

            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);

            Optional<String> direct = directAnswer(ctx, toolUses, results);
            if (direct.isPresent()) {
                return StageResult.direct(direct.get());
            }
            if (round % PROGRESS_EVERY_ROUNDS == 0) {
                remind(ctx, progressPrompt(steps, done));
            }
        }
        return StageResult.completed();
    }

    /** 校验阶段：让模型以验收者身份独立判断产出是否达标。 */
    private Verdict verify(LoopContext ctx) {
        remind(ctx, VERIFY_PROMPT);
        String text = continueToConclusion(ctx);
        if (text == null) {
            // 预算耗尽，拿不到结论；不阻塞流程，由调用方检查预算后如实标记中断
            return new Verdict(true, "");
        }
        return parseVerdict(text);
    }

    /** 修复阶段：带着具体的失败原因回到工作状态。 */
    private StageResult repair(LoopContext ctx, Verdict verdict) {
        remind(ctx, repairPrompt(verdict.detail()));
        String text = continueToConclusion(ctx);
        if (text == null) {
            return StageResult.truncated();
        }
        return StageResult.completed();
    }

    /** 汇总阶段：给出面向用户的最终答案（仍允许补最后一次工具调用）。 */
    private LoopResult synthesize(LoopContext ctx) {
        remind(ctx, SYNTHESIS_PROMPT);
        return runToolCallingLoop(ctx);
    }

    // ------------------------------------------------------------------
    // 校验结论解析
    // ------------------------------------------------------------------

    /** 一次校验的结论。 */
    private record Verdict(boolean passed, String detail) {
    }

    private Verdict parseVerdict(String text) {
        if (text == null || text.isBlank()) {
            return new Verdict(true, "");
        }
        if (FAIL_VERDICT.matcher(text).find()) {
            return new Verdict(false, text);
        }
        if (PASS_VERDICT.matcher(text).find()) {
            return new Verdict(true, text);
        }
        log.debug("[staged] 校验结论无法解析，判定为通过（避免把预算烧在反复验收上）");
        return new Verdict(true, text);
    }

    // ------------------------------------------------------------------
    // 通用推进
    // ------------------------------------------------------------------

    /**
     * 一直推进到模型不再请求工具为止，返回模型最后一段文本。
     *
     * @return 预算耗尽时返回 {@code null}（调用方据此区分"没有结论"与"空结论"）
     */
    private String continueToConclusion(LoopContext ctx) {
        while (true) {
            if (outOfBudget(ctx)) {
                return null;
            }
            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return "";
            }
            List<ToolUsePart> toolUses = message.toolUses();
            if (toolUses.isEmpty()) {
                return message.text();
            }
            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);
            Optional<String> direct = directAnswer(ctx, toolUses, results);
            if (direct.isPresent()) {
                return direct.get();
            }
        }
    }

    private void enter(LoopContext ctx, Stage stage) {
        ctx.attributes().put(STAGE_ATTRIBUTE, stage.wireName());
        ctx.emit("staged.stage", stage.wireName());
        log.debug("[staged] 进入阶段 {}", stage.wireName());
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    private String executionPrompt(List<String> steps) {
        StringBuilder sb = new StringBuilder("这是你刚刚制定的计划，共 ").append(steps.size())
                .append(" 步（当前进度 0/").append(steps.size()).append("）：\n");
        appendSteps(sb, steps, 0);
        sb.append("\n请从第 1 步开始执行：需要工具时直接调用工具，完成一步后用一两句话说明该步结果，")
                .append("然后继续下一步。全部步骤完成后我会做一次独立验收。");
        return sb.toString();
    }

    private String progressPrompt(List<String> steps, int done) {
        int total = steps.size();
        StringBuilder sb = new StringBuilder("已完成 ").append(done).append('/').append(total)
                .append("，剩余步骤：\n");
        appendSteps(sb, steps, done);
        sb.append("\n请继续执行第 ").append(Math.min(done + 1, total)).append(" 步。");
        return sb.toString();
    }

    private String repairPrompt(String detail) {
        return """
                验收未通过，需要修正的问题如下：

                """ + (detail == null || detail.isBlank()
                ? "（验收者未给出具体问题，请你自行复查并修正遗漏）" : detail)
                + """

                请针对上述每一个问题逐一修正，需要工具时直接调用工具。
                修正完成后用一两句话说明你改了什么。我会再验收一次。
                """;
    }

    /** 从 {@code from}（0 基下标）开始列出剩余步骤。 */
    private void appendSteps(StringBuilder sb, List<String> steps, int from) {
        for (int i = from; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // 内部类型与工具
    // ------------------------------------------------------------------

    /** 执行阶段的三种结局。 */
    private enum Status {
        /** 计划全部走完，可进入校验。 */
        COMPLETED,
        /** 某个 returnDirect 工具直接给出了最终答案，跳过后续阶段。 */
        DIRECT,
        /** 步数预算耗尽。 */
        TRUNCATED
    }

    private record StageResult(Status status, String text) {

        static StageResult completed() {
            return new StageResult(Status.COMPLETED, "");
        }

        static StageResult direct(String text) {
            return new StageResult(Status.DIRECT, text == null ? "" : text);
        }

        static StageResult truncated() {
            return new StageResult(Status.TRUNCATED, "");
        }
    }

    private static String abbreviate(String text) {
        return text == null ? "" : Json.abbreviate(text.substring(0, Math.min(text.length(), EVENT_TEXT_LIMIT)));
    }

    private static Map<String, Object> event(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            payload.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return payload;
    }
}
