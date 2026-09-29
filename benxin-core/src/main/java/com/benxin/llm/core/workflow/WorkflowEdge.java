package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Objects;

/**
 * 工作流的一条有向边。
 *
 * <p>{@code when} 为空表示"无条件"，即默认分支。同一个节点可以有多条出边，
 * 引擎按<b>声明顺序</b>取第一条条件成立的走 —— 顺序即优先级，不额外引入 {@code priority} 字段：
 * 一个能一眼看出来的顺序，比一个需要比大小的数字更好维护。</p>
 *
 * <p>把默认分支写在最后一条，语义就是"以上都不满足时走这里"，这是最容易读的写法。</p>
 */
@JsonDeserialize(builder = WorkflowEdge.Builder.class)
public final class WorkflowEdge {

    private final String from;
    private final String to;
    private final String when;
    private final String label;

    private WorkflowEdge(Builder b) {
        this.from = b.from == null ? null : b.from.trim();
        this.to = b.to == null ? null : b.to.trim();
        this.when = b.when;
        this.label = b.label;
    }

    public static Builder builder(String from, String to) {
        return new Builder().from(from).to(to);
    }

    /** 无条件边（默认分支）。 */
    public static WorkflowEdge of(String from, String to) {
        return builder(from, to).build();
    }

    /** 带条件的边。 */
    public static WorkflowEdge when(String from, String to, String when) {
        return builder(from, to).when(when).build();
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    /** 条件表达式；{@code null} / 空白 表示无条件成立。 */
    public String when() {
        return when;
    }

    public String label() {
        return label;
    }

    /** 是否是无条件边。 */
    public boolean unconditional() {
        return when == null || when.isBlank();
    }

    void validate(String where) {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException(where + " 的边缺少 from");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException(where + " 的边 [" + from + " -> ?] 缺少 to");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof WorkflowEdge other
                && Objects.equals(from, other.from)
                && Objects.equals(to, other.to)
                && Objects.equals(when, other.when);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, when);
    }

    @Override
    public String toString() {
        return from + " -> " + to + (unconditional() ? "" : " [" + when + "]");
    }

    /** 构建器；同时作为 Jackson 的反序列化目标。 */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String from;
        private String to;
        private String when;
        private String label;

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder when(String when) {
            this.when = when;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public WorkflowEdge build() {
            return new WorkflowEdge(this);
        }
    }
}
