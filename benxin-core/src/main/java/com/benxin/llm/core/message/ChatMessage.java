package com.benxin.llm.core.message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 统一消息。本心内部只流转这一种消息结构，协议差异全部收敛在 Codec 层。
 */
public final class ChatMessage {

    private final Role role;
    private final List<ContentPart> parts;
    private final String name;
    private final String toolCallId;

    private ChatMessage(Role role, List<ContentPart> parts, String name, String toolCallId) {
        this.role = Objects.requireNonNull(role, "role");
        this.parts = Collections.unmodifiableList(new ArrayList<>(parts == null ? List.of() : parts));
        this.name = name;
        this.toolCallId = toolCallId;
    }

    // ---------- 工厂方法 ----------

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, List.of(new TextPart(text)), null, null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, List.of(new TextPart(text)), null, null);
    }

    public static ChatMessage user(ContentPart... parts) {
        return new ChatMessage(Role.USER, Arrays.asList(parts), null, null);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, List.of(new TextPart(text)), null, null);
    }

    public static ChatMessage assistant(String text, List<ToolUsePart> toolUses) {
        List<ContentPart> all = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            all.add(new TextPart(text));
        }
        if (toolUses != null) {
            all.addAll(toolUses);
        }
        return new ChatMessage(Role.ASSISTANT, all, null, null);
    }

    public static ChatMessage assistant(List<ContentPart> parts) {
        return new ChatMessage(Role.ASSISTANT, parts, null, null);
    }

    public static ChatMessage toolResult(String toolUseId, String name, String content, boolean error) {
        return new ChatMessage(Role.TOOL, List.of(new ToolResultPart(toolUseId, name, content, error)),
                name, toolUseId);
    }

    public static ChatMessage of(Role role, String text) {
        return new ChatMessage(role, List.of(new TextPart(text)), null, null);
    }

    public static Builder builder(Role role) {
        return new Builder(role);
    }

    // ---------- 访问器 ----------

    public Role role() {
        return role;
    }

    public List<ContentPart> parts() {
        return parts;
    }

    public String name() {
        return name;
    }

    public String toolCallId() {
        return toolCallId;
    }

    /**
     * 消息的"可见正文"。
     *
     * <p>只拼接 {@link TextPart}，以及工具消息的 {@link ToolResultPart} 内容。
     * <b>刻意排除思维链与工具调用块</b>：否则 {@code assistant.text()} 会把模型的
     * 推理过程和 {@code [工具名(参数)]} 一起拼进来，导致"最终答案"里混入噪声，
     * 也会让"模型是否已经给出结论"这类判断失真。</p>
     *
     * <p>需要完整的调试视图请用 {@link #allText()}。</p>
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                sb.append(textPart.text());
            } else if (part instanceof ToolResultPart resultPart) {
                sb.append(resultPart.content());
            }
        }
        return sb.toString();
    }

    /** 包含思维链与工具调用在内的全部内容，仅用于日志、审计与调试。 */
    public String allText() {
        return parts.stream().map(ContentPart::asText).collect(Collectors.joining());
    }

    /** 提取所有工具调用块。 */
    public List<ToolUsePart> toolUses() {
        return parts.stream()
                .filter(ToolUsePart.class::isInstance)
                .map(ToolUsePart.class::cast)
                .toList();
    }

    /** 提取思维链文本（可能为空）。 */
    public String thinking() {
        return parts.stream()
                .filter(ThinkingPart.class::isInstance)
                .map(ContentPart::asText)
                .collect(Collectors.joining());
    }

    public boolean hasToolUse() {
        return parts.stream().anyMatch(ToolUsePart.class::isInstance);
    }

    public boolean isTextEmpty() {
        return text().isEmpty() && toolUses().isEmpty() && thinking().isEmpty();
    }

    /** 返回一个仅替换文本内容的副本，其余结构保持不变。 */
    public ChatMessage withText(String newText) {
        List<ContentPart> rebuilt = new ArrayList<>();
        rebuilt.add(new TextPart(newText));
        parts.stream()
                .filter(p -> !(p instanceof TextPart))
                .forEach(rebuilt::add);
        return new ChatMessage(role, rebuilt, name, toolCallId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatMessage other)) {
            return false;
        }
        return role == other.role && parts.equals(other.parts) && Objects.equals(name, other.name)
                && Objects.equals(toolCallId, other.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, parts, name, toolCallId);
    }

    @Override
    public String toString() {
        String body = allText();
        if (body.length() > 200) {
            body = body.substring(0, 200) + "...";
        }
        return role.wireName() + ": " + body + (hasToolUse() ? " +" + toolUses().size() + " 个工具调用" : "");
    }

    /** 消息构建器。 */
    public static final class Builder {
        private final Role role;
        private final List<ContentPart> parts = new ArrayList<>();
        private String name;
        private String toolCallId;

        private Builder(Role role) {
            this.role = role;
        }

        public Builder text(String text) {
            parts.add(new TextPart(text));
            return this;
        }

        public Builder part(ContentPart part) {
            parts.add(part);
            return this;
        }

        public Builder parts(List<? extends ContentPart> more) {
            parts.addAll(more);
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(role, parts, name, toolCallId);
        }
    }
}