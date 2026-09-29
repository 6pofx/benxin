package com.benxin.llm.core.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流模板插值：把 {@code ${...}} 占位符替换成黑板上的值。
 *
 * <p><b>一个刻意的选择：解析不到的占位符保持原样，而不是替换成空串。</b>
 * 理由是两种失败的样子差别很大 —— 原样保留会让 {@code ${anaylze}} 这样的拼写错误
 * 在提示词与轨迹里一眼可见；换成空串则会静默地让提示词缺一块，模型只能靠猜，
 * 排查时也看不到任何线索。同时这也让提示词里合法出现的 {@code ${VAR}}
 * （shell 片段、模板示例）不会被误伤，省掉了一套转义语法。</p>
 *
 * <p>路径用点号分层：{@code ${step1.data}} 会先取 {@code step1}，再在其中按
 * {@link Map} 逐层下钻。取不到值同样保留原文。</p>
 */
public final class Templates {

    /** 占位符：{@code ${path}} 或带兜底值的 {@code ${path:-默认}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]*)}");

    private Templates() {
    }

    /**
     * 渲染模板。
     *
     * @param template 可能是 {@code null} 的模板
     * @param lookup   按<b>顶层键</b>取值；返回 {@code null} 视为"没取到"
     */
    public static String render(String template, Function<String, Object> lookup) {
        if (template == null || template.isEmpty() || lookup == null) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String body = matcher.group(1);
            String path = body;
            String fallback = null;
            int separator = body.indexOf(":-");
            if (separator >= 0) {
                path = body.substring(0, separator);
                fallback = body.substring(separator + 2);
            }
            String value = stringify(resolve(path.trim(), lookup));
            if (value == null) {
                value = fallback;
            }
            // 一个都取不到就整体保留原文（含 ${} 外壳），让错误可见
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String render(String template, Map<String, Object> vars) {
        return render(template, key -> vars == null ? null : vars.get(key));
    }

    /**
     * 按路径取值。
     *
     * <p>首段交给 {@code lookup}（黑板的键就是节点 id 与变量名），其余各段在
     * {@link Map} 里下钻。任何一段缺失都返回 {@code null}。</p>
     */
    public static Object resolve(String path, Function<String, Object> lookup) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return lookup.apply(path);
        }
        Object current = lookup.apply(path.substring(0, dot));
        int index = dot + 1;
        while (current != null && index <= path.length()) {
            int next = path.indexOf('.', index);
            String segment = next < 0 ? path.substring(index) : path.substring(index, next);
            if (segment.isEmpty()) {
                return null;
            }
            current = step(current, segment);
            if (next < 0) {
                break;
            }
            index = next + 1;
        }
        return current;
    }

    /** 在集合/数组中取一段：Map 按键，List 按数字下标。 */
    private static Object step(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        if (current instanceof List<?> list) {
            try {
                int i = Integer.parseInt(segment);
                return i >= 0 && i < list.size() ? list.get(i) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 把值渲染成字符串；{@code null} 返回 {@code null} 以便调用方区分"空"与"没取到"。 */
    public static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return com.benxin.llm.core.util.Json.writeQuietly(value);
    }

    /** 递归渲染工具参数：字符串按模板替换，容器逐层下钻，其余原样保留。 */
    public static Map<String, Object> renderArgs(Map<String, Object> args, Function<String, Object> lookup) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        if (args != null) {
            args.forEach((key, value) -> rendered.put(key, renderValue(value, lookup)));
        }
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private static Object renderValue(Object value, Function<String, Object> lookup) {
        if (value instanceof String text) {
            return render(text, lookup);
        }
        if (value instanceof Map<?, ?> map) {
            return renderArgs((Map<String, Object>) map, lookup);
        }
        if (value instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderValue(item, lookup));
            }
            return rendered;
        }
        return value;
    }

    /**
     * 列出模板里引用的全部顶层键名（去掉 {@code :-默认} 尾巴）。
     *
     * <p>带兜底值的占位符不算硬依赖，因此不会出现在结果里。引擎与加载期校验都不使用它 ——
     * {@code set} 节点可以凭空创造变量，硬性检查引用会误报；这里把它开放出来，
     * 是给外部的校验器、图可视化与文档生成用的。</p>
     */
    public static List<String> placeholders(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body.contains(":-")) {
                continue;
            }
            String key = body.trim();
            int dot = key.indexOf('.');
            if (dot > 0) {
                key = key.substring(0, dot);
            }
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }
}
