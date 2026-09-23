package com.benxin.llm.core.prompt;

import com.benxin.llm.core.agent.AgentSpec;

import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认系统提示词实现：把 {@code {var}} 占位符替换为上下文变量，
 * 并内置几个常用变量（{@code {agentName}}、{@code {today}}、{@code {tools}}、{@code {cwd}}）。
 *
 * <p>未知占位符原样保留，方便排查拼写错误。</p>
 */
public class TemplateSystemPromptProvider implements SystemPromptProvider {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private final String prefixSuffixHeader;

    public TemplateSystemPromptProvider() {
        this(null);
    }

    public TemplateSystemPromptProvider(String prefixSuffixHeader) {
        this.prefixSuffixHeader = prefixSuffixHeader;
    }

    @Override
    public String systemPrompt(AgentSpec spec, Map<String, Object> vars) {
        String template = spec == null ? null : spec.systemPrompt();
        if (template == null || template.isBlank()) {
            return null;
        }
        return render(template, spec, vars);
    }

    /** 对任意模板做插值，供 Loop 拼装内置提示词时复用。 */
    public String render(String template, AgentSpec spec, Map<String, Object> vars) {
        if (template == null) {
            return null;
        }
        StringBuilder tools = new StringBuilder();
        if (spec != null && spec.toolNames() != null) {
            tools.append(String.join(", ", spec.toolNames()));
        }
        Map<String, Object> builtin = new java.util.LinkedHashMap<>();
        builtin.put("today", LocalDate.now().toString());
        builtin.put("agentName", spec == null ? "" : spec.name());
        builtin.put("loop", spec == null ? "" : spec.loop());
        builtin.put("tools", tools.toString());
        builtin.put("cwd", System.getProperty("user.dir", ""));
        builtin.put("os", System.getProperty("os.name", ""));
        if (vars != null) {
            builtin.putAll(vars);
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = builtin.get(key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    value == null ? matcher.group(0) : String.valueOf(value)));
        }
        matcher.appendTail(out);

        String rendered = out.toString();
        if (prefixSuffixHeader != null && !prefixSuffixHeader.isBlank()) {
            rendered = prefixSuffixHeader + "\n\n" + rendered;
        }
        return rendered;
    }
}