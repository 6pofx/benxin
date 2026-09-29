package com.benxin.llm.core.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型回复里抽出编号计划。
 *
 * <p>原本这段逻辑长在 {@code PlanExecuteLoop} 里；出现第二个需要"先规划后执行"的 Loop
 * （{@code staged}）之后，把它提出来共用 —— 这类容错正则一旦有两份拷贝，
 * 就一定会出现"一个修了、另一个没修"的分叉。</p>
 *
 * <p><b>宽容策略</b>：只认"带列表前缀"的行，其余行（标题、前言、"计划如下："一类过渡句）
 * 一律忽略，因为把说明文字当成步骤比漏掉一步更糟。一条都抽不出来时返回空列表，
 * 由调用方决定降级方式。</p>
 */
public final class PlanParser {

    /** 计划最多保留的步骤数：防止模型输出超长计划，把每轮进度提醒撑成上下文炸弹。 */
    public static final int MAX_STEPS = 50;

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

    private PlanParser() {
    }

    /** 解析步骤列表；解析不出任何步骤时返回空列表。 */
    public static List<String> parse(String text) {
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
            if (steps.size() >= MAX_STEPS) {
                break;
            }
        }
        return steps;
    }

    /** 计划是否达到保留上限（调用方可用它判断"已截断"）。 */
    public static boolean truncated(List<String> steps) {
        return steps.size() >= MAX_STEPS;
    }
}
