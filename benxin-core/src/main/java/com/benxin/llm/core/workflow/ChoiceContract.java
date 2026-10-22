package com.benxin.llm.core.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 节点输出的<b>枚举契约</b>：把"模型应该只输出这三个词之一"从"提示词里的请求"变成
 * "框架执行的校验"。
 *
 * <p>为什么必须有这个：提示词约束不了模型。它会在词前后加句号、套一层 {@code **}、
 * 包进 Markdown 代码块、先客气一句"好的，我判断结果是……"，甚至把"不通过"写成
 * 看起来像"通过"的样子。下游若直接按字符串分支，这些全是静默走错路。</p>
 *
 * <p>这里的做法分两步，都是纯代码、不依赖厂商特性：</p>
 * <ol>
 *   <li><b>归一化</b>：去掉代码围栏、加粗、列表前缀、常见前缀词（"结论："）、
 *       引号括号、尾部标点，再 trim；</li>
 *   <li><b>判定</b>：先精确匹配，再"唯一子串匹配"，并对否定说法（"不通过"之于"通过"）
 *       与包含歧义（{@code 通过} / {@code 不通过} 同时出现）一律判为<b>不匹配</b>。</li>
 * </ol>
 *
 * <p>不匹配时由调用方（{@code LoopContextRuntime}）追加一条纠正消息让模型重答，
 * 重试用尽才让节点失败 —— <b>宁可失败，也不把一个模糊答案当成枚举值塞进图里</b>。</p>
 */
public final class ChoiceContract {

    /** 允许的纠正重试次数。每次重试都是一次额外模型调用，因此刻意很小。 */
    public static final int DEFAULT_RETRIES = 2;

    /** Markdown 代码围栏：取围栏里的内容（模型经常把答案包在 ``` 里）。 */
    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z0-9_-]*\\s*\\n?(.*?)```", Pattern.DOTALL);

    /** 常见前缀：模型爱写"结论：""答案："再给词。 */
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:结论|判定|判断|审核结论|结果|答案|输出|answer|result)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);

    /** 列表前缀。 */
    private static final Pattern BULLET = Pattern.compile("^[-*•·]\\s+");

    /** 首尾的引号与括号。 */
    private static final Pattern LEADING_NOISE = Pattern.compile("^[\"'`\\u201c\\u201d\\u2018\\u2019「」『』]*");
    private static final Pattern TRAILING_NOISE = Pattern.compile("[\"'`\\u201c\\u201d\\u2018\\u2019「」『』]*$");

    /** 尾部标点。 */
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[。.!！,，;；:：、\\s]+$");

    /** 否定前缀：这些字紧挨在选项前面时，那个选项不算命中。 */
    private static final String[] NEGATIONS = {"不", "未", "非", "没"};

    private ChoiceContract() {
    }

    /**
     * 把模型原文归一化成一个干净的词。
     *
     * <p>顺序有讲究：先取围栏内容（否则围栏符号会干扰后面的去噪），再去强调与列表前缀，
     * 再去"结论："这类前缀（可能叠两层），最后去引号与尾标点。</p>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").trim();
        Matcher fence = FENCE.matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        }
        text = text.replace("**", "").replace("__", "").replace("`", "");
        text = BULLET.matcher(text).replaceFirst("");
        // 前缀可能叠两层（"结论：答案是……"）
        for (int i = 0; i < 2; i++) {
            String stripped = PREFIX.matcher(text).replaceFirst("");
            if (stripped.equals(text)) {
                break;
            }
            text = stripped;
        }
        text = LEADING_NOISE.matcher(text).replaceAll("");
        text = TRAILING_NOISE.matcher(text).replaceAll("");
        text = TRAILING_PUNCT.matcher(text).replaceAll("");
        return text.trim();
    }

    /**
     * 从模型原文里判定出枚举值。
     *
     * @param raw     模型原文
     * @param choices 允许的取值；为空表示没有契约，原样返回 {@code raw}
     * @return 命中的取值；<b>无法无歧义判定时返回 {@code null}</b>
     */
    public static String match(String raw, List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return raw;
        }
        String text = normalize(raw);
        if (text.isEmpty()) {
            return null;
        }
        for (String choice : choices) {
            if (text.equals(choice)) {
                return choice;
            }
        }
        for (String choice : choices) {
            if (text.equalsIgnoreCase(choice)) {
                return choice;
            }
        }

        // 唯一子串匹配：先剔除"被否定"的，再剔除"是别的命中项子串"的
        List<String> present = new ArrayList<>();
        for (String choice : choices) {
            if (text.contains(choice) && !negated(text, choice)) {
                present.add(choice);
            }
        }
        List<String> decisive = new ArrayList<>();
        for (String choice : present) {
            boolean subsumed = present.stream()
                    .anyMatch(other -> !other.equals(choice) && other.contains(choice));
            if (!subsumed) {
                decisive.add(choice);
            }
        }
        return decisive.size() == 1 ? decisive.get(0) : null;
    }

    /** 模型原文里，这个选项是否被"不/未/非/没"否定掉了。 */
    private static boolean negated(String text, String choice) {
        for (String negation : NEGATIONS) {
            if (text.contains(negation + choice)) {
                return true;
            }
        }
        return false;
    }

    /** 生成一条纠正消息：说清允许值，并要求只输出一个词。 */
    public static String correction(List<String> choices) {
        return "你上一条回复不在允许的取值里。允许取值只有：" + String.join(" / ", choices)
                + "。请只输出其中一个词：不要加标点、不要解释、不要加前缀、不要用 Markdown 代码块。";
    }
}
