package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 工作流的一条有向边。
 *
 * <p>条件有两种写法，<b>二选一</b>：</p>
 * <ul>
 *   <li>{@link #when()} —— 给 YAML/JSON 用的表达式小语言，是<b>字符串</b>，可序列化；</li>
 *   <li>{@link #condition()} —— 给代码编排用的 {@code Predicate<FlowView>}，是<b>Java 代码</b>，
 *       类型安全、可重构，但不可序列化。</li>
 * </ul>
 *
 * <p>两者都为空表示"无条件"，即默认分支。同一个节点可以有多条出边，引擎按<b>声明顺序</b>
 * 取第一条成立的走 —— 顺序即优先级，不额外引入 {@code priority} 字段：
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
    private final Predicate<FlowView> condition;

    private WorkflowEdge(Builder b) {
        this.from = b.from == null ? null : b.from.trim();
        this.to = b.to == null ? null : b.to.trim();
        this.when = b.when;
        this.label = b.label;
        this.condition = b.condition;
    }

    public static Builder builder(String from, String to) {
        return new Builder().from(from).to(to);
    }

    /** 无条件边（默认分支）。 */
    public static WorkflowEdge of(String from, String to) {
        return builder(from, to).build();
    }

    /** 带表达式条件的边（YAML/JSON 用）。 */
    public static WorkflowEdge when(String from, String to, String when) {
        return builder(from, to).when(when).build();
    }

    /** 带 Java 谓词条件的边（代码编排用）。 */
    public static WorkflowEdge when(String from, String to, Predicate<FlowView> condition) {
        return builder(from, to).condition(condition).build();
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

    /**
     * Java 谓词条件；只有代码编排才会设置。
     *
     * <p>它不参与序列化 —— {@link WorkflowIo} 遇到带谓词的边会明确报错，
     * 而不是把一条有条件的边悄悄写成无条件边（那会静默改变图的语义）。</p>
     */
    public Predicate<FlowView> condition() {
        return condition;
    }

    public String label() {
        return label;
    }

    /** 是否是无条件边（两种条件都没有）。 */
    public boolean unconditional() {
        return condition == null && (when == null || when.isBlank());
    }

    /**
     * 这条边在当前黑板上是否成立。
     *
     * <p>谓词优先于表达式：两者互斥，{@link #validate} 已经拦下同时设置的情况。</p>
     */
    public boolean matches(WorkflowState state) {
        if (condition != null) {
            return condition.test(FlowView.of(state));
        }
        return Conditions.evaluate(when, state.lookup());
    }

    void validate(String where) {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException(where + " 的边缺少 from");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException(where + " 的边 [" + from + " -> ?] 缺少 to");
        }
        if (condition != null && when != null && !when.isBlank()) {
            throw new IllegalArgumentException(where + " 的边 [" + from + " -> " + to
                    + "] 同时设置了 when 表达式与 Java 谓词，只能二选一");
        }
        Conditions.validate(when);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        // 谓词是 lambda，身份不稳定；这里只按"结构"比较，与序列化口径一致
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
        if (condition != null) {
            return from + " -> " + to + " [λ]";
        }
        return from + " -> " + to + (unconditional() ? "" : " [" + when + "]");
    }

    /** 构建器；同时作为 Jackson 的反序列化目标。 */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String from;
        private String to;
        private String when;
        private String label;
        private Predicate<FlowView> condition;

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

        /** 只在代码里设置；{@code @JsonIgnore} 让 Jackson 不去尝试实例化一个接口。 */
        @JsonIgnore
        public Builder condition(Predicate<FlowView> condition) {
            this.condition = condition;
            return this;
        }

        public WorkflowEdge build() {
            return new WorkflowEdge(this);
        }
    }
}
