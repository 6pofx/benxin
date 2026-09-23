package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan-and-Execute 循环：先规划，再逐步执行。
 *
 * <p><b>设计来源</b>：Plan-and-Solve / Plan-and-Execute 一系工作。核心观察是：让模型先把任务
 * 拆成一份显式计划，比让它边想边做更不容易漏步，也更容易被人观察与干预 —— 计划一旦落到对话
 * 历史里，就成了一份可审计的清单，人可以中途看到它、也能据此判断 Agent 是否跑偏。</p>
 *
 * <p><b>实现取舍</b>：</p>
 * <ul>
 *   <li>计划用编号文本而不是 JSON 表达：文本计划对模型更友好（模型天然会写列表），解析容错也
 *       更容易做 —— {@link #parsePlan(String)} 兼容 {@code 1.} / {@code 1)} / {@code - } /
 *       {@code * } / {@code 第 1 步：} / {@code Step 1:} 以及 {@code **加粗**} 写法。</li>
 *   <li>计划既写进 {@code ctx.attributes().get("benxin.plan")}，又广播 {@code plan} 事件：
 *       事件用于实时展示（监听器 / UI），attributes 用于同一次运行里的后续读取与最终
 *       {@code LoopResult.attributes()} 留档。</li>
 *   <li>"模型这一轮不再请求工具"被当作"当前这一步做完了"的信号 —— 这是唯一不依赖模型自报
 *       进度的可观测信号。代价是：若模型第一轮就把整个任务答完，它仍会被提醒继续走完剩余步骤，
 *       这是 plan-execute 相对 react 的固有代价（换来的是计划被完整执行）。</li>
 *   <li>全部步骤走完后额外要一轮汇总：否则 {@code LoopResult.text()} 里只有最后一步的文本，
 *       对使用者不友好；这一步直接复用基类的 tool-calling 骨架（允许它补最后一次工具调用）。</li>
 *   <li>计划解析不出来时打 warn 并降级为标准的 tool-calling 循环，绝不空转；
 *       规划阶段模型若违规调用了工具，也会把调用补执行完，避免历史里留下
 *       "有 tool_use 却没有 tool_result"的断裂结构（多数厂商会直接拒绝这种请求）。</li>
 * </ul>
 */
@LlmLoop("plan-execute")
public class PlanExecuteLoop extends AbstractAgentLoop {

    /** 计划在 {@code ctx.attributes()} 中的键名。 */
    public static final String PLAN_ATTRIBUTE = "benxin.plan";

    /** 即使模型一直在调工具，每经过这么多轮也回显一次进度，防止长任务跑偏。 */
    private static final int PROGRESS_EVERY_ROUNDS = 3;

    /** 计划最多保留的步骤数：防止模型输出超长计划，把每轮进度提醒撑成上下文炸弹。 */
    private static final int MAX_PLAN_STEPS = 50;

    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:\\d+\\s*[.、)）:：]"
                    + "|[-*•·]"
                    + "|第\\s*[0-9一二三四五六七八九十]+\\s*步(?:骤)?\\s*[.、:：)）]?"
                    + "|步骤\\s*\\d+\\s*[.、:：)）]?"
                    + "|step\\s*\\d+\\s*[.、:：)）]?)"
                    + "\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    /** 形如 {@code **1. 读取配置**} 的加粗条目，先剥壳再匹配列表前缀。 */
    private static final Pattern BOLD_ITEM = Pattern.compile(
            "^\\s*(?:\\*\\*|__)(.+?)(?:\\*\\*|__)\\s*[:：]?\\s*(.*)$");

    /** 纯标题行（"计划：" / "Plan:" / "步骤" / "# 计划"）不算步骤。 */
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*(?:#+\\s*)?(?:计划|执行计划|步骤|任务|plan|steps?|todo|to-do)\\s*[:：]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final String PLANNING_PROMPT = """
            请先针对上面的任务制定一份可执行的编号计划。现在不要开始执行，也不要调用任何工具。

            输出要求：
            1. 只输出计划本身，一行一个步骤，使用 "1. 步骤描述" 的格式；
            2. 每一步都要具体到可以独立执行，建议 2 到 8 步；
            3. 不要输出前言、解释或结尾总结。

            我会在你给出计划后逐步要求你执行。
            """;

    private static final String SYNTHESIS_PROMPT = """
            计划的全部步骤都已执行完毕。请综合前面各步的结果，直接给出面向用户的最终答案。
            不要再调用工具，除非确实还缺少关键信息。
            """;

    @Override
    public String name() {
        return "plan-execute";
    }

    @Override
    public String description() {
        return "先规划后执行：第一步由模型产出编号计划，之后逐步推进并在每步后回显进度。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        if (outOfBudget(ctx)) {
            return finishTruncated(ctx);
        }

        List<String> steps = planning(ctx);
        if (steps.isEmpty()) {
            log.warn("[plan-execute] 模型没有产出可解析的编号计划，降级为直接执行（标准 tool-calling 循环）");
            return runToolCallingLoop(ctx);
        }

        ctx.attributes().put(PLAN_ATTRIBUTE, List.copyOf(steps));
        ctx.emit("plan", List.copyOf(steps));
        log.debug("[plan-execute] 计划解析出 {} 步", steps.size());

        remind(ctx, executionPrompt(steps));
        return execute(ctx, steps);
    }

    // ------------------------------------------------------------------
    // 规划阶段
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
            log.debug("[plan-execute] 规划阶段模型请求了 {} 个工具调用，补执行以保持历史完整",
                    message.toolUses().size());
            ctx.executeTools(message.toolUses()).forEach(ctx::append);
        }
        return parsePlan(message.text());
    }

    /**
     * 从模型回复里抽出步骤列表。
     *
     * <p>宽容策略：只认"带列表前缀"的行，其余行（标题、前言、"计划如下："一类过渡句）一律忽略，
     * 因为把说明文字当成步骤比漏掉一步更糟。一条都抽不出来时返回空列表，由调用方降级处理。</p>
     */
    private List<String> parsePlan(String text) {
        List<String> steps = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return steps;
        }
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || HEADER.matcher(line).matches()) {
                continue;
            }
            String candidate = line;
            Matcher bold = BOLD_ITEM.matcher(line);
            if (bold.matches()) {
                String head = bold.group(1).strip();
                String tail = bold.group(2).strip();
                candidate = tail.isEmpty() ? head : head + "：" + tail;
            }
            Matcher item = LIST_ITEM.matcher(candidate);
            if (!item.matches()) {
                continue;
            }
            String step = item.group(1).strip();
            if (step.isEmpty()) {
                continue;
            }
            steps.add(step);
            if (steps.size() >= MAX_PLAN_STEPS) {
                log.debug("[plan-execute] 计划超过 {} 步，已截断", MAX_PLAN_STEPS);
                break;
            }
        }
        return steps;
    }

    // ------------------------------------------------------------------
    // 执行阶段
    // ------------------------------------------------------------------

    private LoopResult execute(LoopContext ctx, List<String> steps) {
        int total = steps.size();
        int done = 0;
        int round = 0;

        while (true) {
            if (outOfBudget(ctx)) {
                return finishTruncated(ctx);
            }
            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return buildResult(ctx, "");
            }
            round++;

            List<ToolUsePart> toolUses = message.toolUses();
            if (toolUses.isEmpty()) {
                // 不再请求工具 = 当前这一步做完了
                done = Math.min(done + 1, total);
                ctx.emit("plan-step", done);
                if (done >= total) {
                    remind(ctx, SYNTHESIS_PROMPT);
                    return runToolCallingLoop(ctx);
                }
                remind(ctx, progressPrompt(steps, done));
                continue;
            }

            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);

            Optional<String> direct = directAnswer(ctx, toolUses, results);
            if (direct.isPresent()) {
                return buildResult(ctx, direct.get());
            }
            if (round % PROGRESS_EVERY_ROUNDS == 0) {
                remind(ctx, progressPrompt(steps, done));
            }
        }
    }

    private String executionPrompt(List<String> steps) {
        StringBuilder sb = new StringBuilder("这是你刚刚制定的计划，共 ").append(steps.size())
                .append(" 步（当前进度 0/").append(steps.size()).append("）：\n");
        appendSteps(sb, steps, 0);
        sb.append("\n请从第 1 步开始执行：需要工具时直接调用工具，完成一步后用一两句话说明该步结果，")
                .append("然后继续下一步。");
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

    /** 从 {@code from}（0 基下标）开始列出剩余步骤。 */
    private void appendSteps(StringBuilder sb, List<String> steps, int from) {
        for (int i = from; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
    }
}
