package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reflexion 循环：执行 → 自省评分 → 不满意就带着批评重做，最多 N 轮。
 *
 * <p><b>设计来源</b>：Shinn 等人 2023 年的 Reflexion —— 用语言化的自我反馈代替梯度更新，
 * 让 Agent 在不改模型权重的前提下从失败中改进。本类取它的最小可用形态：每轮先完整作答，
 * 再让模型给自己打分并写下批评，分数不到阈值就把批评当作下一轮的输入重做。</p>
 *
 * <p><b>为什么解析不到分数时判定为"满意"</b>：自省本身也要靠模型遵从格式。如果它没有按
 * {@code SCORE:} / {@code CRITIQUE:} 输出，我们既拿不到分数、也拿不到可用于改进的批评，
 * 此时重试只会把一个"格式问题"放大成 N 次无意义的重复调用，还会让 Token 成本成倍上涨。
 * 因此解析失败被保守地当作终止条件 —— 宁可少一轮，不要空转。这也是本类与论文原版最大的
 * 差异：这里把"格式遵从"视为模型能力的一部分，而不是必须纠正的错误。</p>
 *
 * <p><b>其他取舍</b>：</p>
 * <ul>
 *   <li>不复用 {@code runToolCallingLoop}：那个骨架一拿到答案就用 {@code buildResult} 收尾，
 *       而这里需要在答案之后插入一轮自省，所以内层循环自己写（逻辑与它保持一致：
 *       有工具调用就执行并继续，没有就是候选答案，{@code returnDirect} 工具直接交付）。</li>
 *   <li>最后一轮同样发一次自省请求：多花一次模型调用，换来统一的语义与可观测的最终评分 ——
 *       即使结果注定被标记为 {@code benxin.reflexion.exhausted}。若想省这一次调用，
 *       把 {@code maxAttempts} 设为 1 即可退化成普通循环。</li>
 *   <li>自省与重做都吃同一条步数预算（{@code ctx.maxSteps()}），预算太小时会提前截断；
 *       截断时返回历史里最后一版答案，并把已用轮次写进 attributes。</li>
 *   <li>失败路径才写 {@code benxin.reflexion.exhausted}（值为 {@code true}）：键不存在即表示
 *       在阈值内收敛，比显式写 {@code false} 更容易在外部按"是否存在"判断。</li>
 * </ul>
 */
@LlmLoop("reflexion")
public class ReflexionLoop extends AbstractAgentLoop {

    /** 实际执行的作答轮数。 */
    public static final String ATTEMPTS_ATTRIBUTE = "benxin.reflexion.attempts";
    /** 最后一轮的自评分（0-1）；模型没按格式给分时不写入。 */
    public static final String SCORE_ATTRIBUTE = "benxin.reflexion.score";
    /** 用尽 maxAttempts 仍未达标时写入 {@code true}。 */
    public static final String EXHAUSTED_ATTRIBUTE = "benxin.reflexion.exhausted";

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final double DEFAULT_REFLECTION_THRESHOLD = 0.8;
    /** 回灌给模型的批评文本长度上限，防止自省段落把下一轮上下文撑爆。 */
    private static final int CRITIQUE_LIMIT = 1200;

    /** 自评分：兼容 SCORE: / 评分： / 得分 = / Score = 等写法，可选百分比与 x/y 形式。 */
    private static final Pattern SCORE_PATTERN = Pattern.compile(
            "(?im)(?:score|rating|评分|得分|分数)\\s*[:：=＝]?\\s*(\\d+(?:\\.\\d+)?)\\s*(%|/\\s*(\\d+(?:\\.\\d+)?))?");

    /** 批评：兼容 CRITIQUE: / 批评： / 改进建议： 等写法。 */
    private static final Pattern CRITIQUE_PATTERN = Pattern.compile(
            "(?is)(?:critique|criticism|feedback|批评|自省|意见|建议)\\s*[:：=＝]\\s*(.+)$");

    /** 兜底清理自省文本里的评分行（批评字段缺失时用）。 */
    private static final Pattern SCORE_LINE = Pattern.compile(
            "(?im)^.*(?:score|rating|评分|得分|分数)\\s*[:：=＝].*$");

    private static final String REFLECTION_PROMPT = """
            请评价你刚刚给出的答案是否真正完成了用户的任务。严格按下面两行输出，不要输出其它内容：

            SCORE: <0 到 1 之间的小数，1 表示完全满足要求>
            CRITIQUE: <不满意时说明具体缺什么、下一轮该怎么改；满意时写"无">
            """;

    private final int maxAttempts;
    private final double reflectionThreshold;

    public ReflexionLoop() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_REFLECTION_THRESHOLD);
    }

    /**
     * @param maxAttempts         最多作答几轮（含第一轮），至少为 1
     * @param reflectionThreshold 自评分达标线，取值 0-1；低于它才带着批评重做
     */
    public ReflexionLoop(int maxAttempts, double reflectionThreshold) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.reflectionThreshold = Math.max(0d, Math.min(1d, reflectionThreshold));
    }

    @Override
    public String name() {
        return "reflexion";
    }

    @Override
    public String description() {
        return "执行 → 自省评分 → 不满意则带着批评重做，最多 N 轮。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        String answer = "";
        double score = Double.NaN;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Optional<String> candidate = executeAttempt(ctx);
            if (candidate.isEmpty()) {
                // 步数预算在作答途中耗尽：带着已有信息收尾
                record(ctx, attempt, Double.NaN);
                return finishTruncated(ctx);
            }
            answer = candidate.get();

            if (outOfBudget(ctx)) {
                record(ctx, attempt, Double.NaN);
                return finishTruncated(ctx);
            }

            remind(ctx, REFLECTION_PROMPT);
            Reflection reflection = parseReflection(ctx.callModel().text());
            score = reflection.score();
            log.debug("[reflexion] 第 {}/{} 轮自评分: {}", attempt, maxAttempts,
                    Double.isNaN(score) ? "未解析（按满意处理）" : score);
            if (!Double.isNaN(score)) {
                ctx.emit("reflexion-score", Map.of("attempt", attempt, "score", score));
            }

            if (reflection.satisfied(reflectionThreshold)) {
                record(ctx, attempt, score);
                return buildResult(ctx, answer);
            }
            if (attempt < maxAttempts) {
                remind(ctx, retryPrompt(reflection.critique(), score));
            }
        }

        log.warn("[reflexion] {} 轮尝试后自评分仍未达到阈值 {}，返回最后一轮答案", maxAttempts, reflectionThreshold);
        record(ctx, maxAttempts, score);
        ctx.attributes().put(EXHAUSTED_ATTRIBUTE, Boolean.TRUE);
        return buildResult(ctx, answer);
    }

    /**
     * 一轮完整作答：调模型 → 有工具调用就执行 → 直到模型不再请求工具。
     *
     * @return 候选答案；步数预算耗尽时返回 {@link Optional#empty()}
     */
    private Optional<String> executeAttempt(LoopContext ctx) {
        while (true) {
            if (outOfBudget(ctx)) {
                return Optional.empty();
            }
            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return Optional.of("");
            }
            List<ToolUsePart> toolUses = message.toolUses();
            if (toolUses.isEmpty()) {
                return Optional.of(message.text());
            }
            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);
            Optional<String> direct = directAnswer(ctx, toolUses, results);
            if (direct.isPresent()) {
                return direct;
            }
        }
    }

    // ------------------------------------------------------------------
    // 自省解析
    // ------------------------------------------------------------------

    /**
     * 一轮自省的结构化结果。
     *
     * @param score    归一化到 0-1 的自评分；{@link Double#NaN} 表示模型没按格式给分
     * @param critique 批评文本，可能为空
     */
    private record Reflection(double score, String critique) {

        /**
         * 是否达标。
         *
         * <p>解析不到分数时一律算达标：既没有分数也没有可用的批评，重试就是纯粹的空转。</p>
         */
        boolean satisfied(double threshold) {
            return Double.isNaN(score) || score >= threshold;
        }
    }

    private Reflection parseReflection(String text) {
        if (text == null || text.isBlank()) {
            return new Reflection(Double.NaN, "");
        }
        return new Reflection(parseScore(text), parseCritique(text));
    }

    /** 取最后一个可识别的评分：模型常常先说一遍再复述一遍，最后一次才是结论。 */
    private double parseScore(String text) {
        Matcher matcher = SCORE_PATTERN.matcher(text);
        double value = Double.NaN;
        while (matcher.find()) {
            value = normalize(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        return value;
    }

    private double normalize(String number, String percentOrRatio, String denominator) {
        try {
            double value = Double.parseDouble(number);
            if (percentOrRatio != null && percentOrRatio.startsWith("%")) {
                value = value / 100d;
            } else if (denominator != null) {
                double divisor = Double.parseDouble(denominator);
                if (divisor == 0d) {
                    return Double.NaN;
                }
                value = value / divisor;
            } else if (value > 1d && value <= 10d) {
                value = value / 10d;      // 兼容 1-10 分制（例如 SCORE: 7）
            } else if (value > 10d && value <= 100d) {
                value = value / 100d;     // 兼容百分制（例如 评分：85）
            }
            return Math.max(0d, Math.min(1d, value));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String parseCritique(String text) {
        Matcher matcher = CRITIQUE_PATTERN.matcher(text);
        if (matcher.find()) {
            String critique = matcher.group(1).strip();
            if (!critique.isEmpty()) {
                return critique;
            }
        }
        // 没有 CRITIQUE 字段：把整段自省文本（去掉评分行）当作批评，别丢掉模型给出的改进信息
        return SCORE_LINE.matcher(text).replaceAll("").strip();
    }

    // ------------------------------------------------------------------
    // 提示词与收尾
    // ------------------------------------------------------------------

    private String retryPrompt(String critique, double score) {
        String advice = critique == null || critique.isBlank()
                ? "（模型没有给出具体批评，请自行检查遗漏、错误与未满足的要求）"
                : abbreviate(critique, CRITIQUE_LIMIT);
        return "你上一轮答案的自评分是 " + format(score) + "，低于达标线 " + format(reflectionThreshold) + "，还不够好。\n\n"
                + "自省意见：\n" + advice + "\n\n"
                + "请据此改进后重新作答，直接给出改进后的完整答案。";
    }

    private void record(LoopContext ctx, int attempts, double score) {
        ctx.attributes().put(ATTEMPTS_ATTRIBUTE, attempts);
        if (!Double.isNaN(score)) {
            ctx.attributes().put(SCORE_ATTRIBUTE, score);
        }
    }

    private static String format(double score) {
        return Double.isNaN(score) ? "未解析" : String.valueOf(score);
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…（已截断）";
    }
}
