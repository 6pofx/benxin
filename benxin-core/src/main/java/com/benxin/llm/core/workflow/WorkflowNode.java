package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工作流中的一个节点。
 *
 * <p>一个类承载全部节点类型，而不是给每种类型建一个子类：节点是<b>数据</b>而不是行为，
 * 用同一个结构能让 YAML / JSON / Java DSL 三条入口共用一套解析，也让"有哪些字段"
 * 这件事在一屏之内看得完。代价是某些字段只对特定类型有意义 —— 这一点交给
 * {@link #validate()} 在加载期一次性拦下，而不是留到运行期才发现。</p>
 *
 * <p>所有文本字段（{@code prompt} / {@code instruction} / {@code output} / {@code args} /
 * {@code set}）都是<b>模板</b>，运行期用黑板变量做 {@code ${...}} 插值，详见
 * {@link Templates}。</p>
 */
@JsonDeserialize(builder = WorkflowNode.Builder.class)
public final class WorkflowNode {

    private final String id;
    private final NodeType type;
    private final String description;
    private final String prompt;
    private final String system;
    private final String instruction;
    private final String tool;
    private final String handler;
    private final WorkflowHandler behavior;
    private final Map<String, Object> args;
    private final Map<String, String> set;
    private final String output;
    private final int retry;
    private final boolean continueOnError;
    private final int maxSteps;
    private final List<String> choice;

    private WorkflowNode(Builder b) {
        this.id = b.id == null ? null : b.id.trim();
        this.type = b.type == null ? NodeType.AGENT : b.type;
        this.description = b.description;
        this.prompt = b.prompt;
        this.system = b.system;
        this.instruction = b.instruction;
        this.tool = b.tool;
        this.handler = b.handler;
        this.behavior = b.behavior;
        this.args = Map.copyOf(b.args);
        this.set = Map.copyOf(b.set);
        this.output = b.output;
        this.retry = Math.max(0, b.retry);
        this.continueOnError = b.continueOnError;
        this.maxSteps = b.maxSteps;
        this.choice = List.copyOf(b.choice);
    }

    public static Builder builder(String id) {
        return new Builder().id(id);
    }

    public String id() {
        return id;
    }

    public NodeType type() {
        return type;
    }

    public String description() {
        return description;
    }

    public String prompt() {
        return prompt;
    }

    /**
     * 本节点的<b>系统提示词</b>（模板）：会追加到本次请求的系统提示词之后，
     * 只在这一节点的模型调用里生效。
     *
     * <p>与 {@link #instruction()} 的区别是这个真的走系统通道 —— 在
     * Anthropic / Gemini 里它是顶层 {@code system} / {@code systemInstruction}，
     * 而不是混在用户消息里的一段前缀。想给某一步换角色、要求它只输出枚举值，
     * 用这个；只是想在用户消息前面加句话，用 {@code instruction}。</p>
     */
    public String system() {
        return system;
    }

    public String instruction() {
        return instruction;
    }

    public String tool() {
        return tool;
    }

    /**
     * JAVA 节点要执行的处理器名（对应 {@link WorkflowHandlerRegistry} 里的键）。
     *
     * <p>刻意是名字而不是类名或方法名：定义只能引用宿主已经注册进来的代码，
     * 这是"一张 YAML 不至于变成任意代码执行入口"的全部依据。</p>
     */
    public String handler() {
        return handler;
    }

    /**
     * CODE 节点直接绑定的行为对象；只有 {@link JavaFlow} 会设置。
     *
     * <p>与 {@link #handler()} 的区别就是"代码编排"与"声明式"的区别：
     * 那个是一个字符串名字，运行期去注册表里查；这个是一个活对象，
     * 可以是 `new` 出来的、也可以是 Spring 注入进来的，能带自己的状态。</p>
     */
    public WorkflowHandler behavior() {
        return behavior;
    }

    public Map<String, Object> args() {
        return args;
    }

    public Map<String, String> set() {
        return set;
    }

    public String output() {
        return output;
    }

    /** 失败后额外重试的次数（0 表示不重试）。 */
    public int retry() {
        return retry;
    }

    /** 节点失败时是否继续沿出边走（false 表示整个工作流以失败终止）。 */
    public boolean continueOnError() {
        return continueOnError;
    }

    /** AGENT 节点的内层工具循环步数上限；{@code -1} 表示沿用 Agent 的全局上限。 */
    public int maxSteps() {
        return maxSteps;
    }

    /**
     * AGENT 节点的<b>输出契约</b>：模型这次必须只吐出其中一个值。
     *
     * <p>空表示没有契约（模型想说什么说什么）。非空时由运行时做<b>归一化 + 判定 + 纠正重试</b>，
     * 见 {@link ChoiceContract} —— 这一步不是提示词，是代码校验：模型加了标点、套了 Markdown、
     * 先客气一句，都会在这里被剥掉或打回重答；重试用尽仍不合规，节点直接失败。</p>
     */
    public List<String> choice() {
        return choice;
    }

    /**
     * 加载期校验：宁可在这里报一句清楚的话，也不要等到跑了一半才崩。
     *
     * @param where 出错位置的前缀（如 {@code 工作流 [review] 的节点 [analyze]}），便于定位
     */
    void validate(String where) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(where + " 缺少 id");
        }
        if (id.contains(".")) {
            // 黑板的键就是节点 id，点号会与"点路径"取值混淆，直接在加载期禁掉
            throw new IllegalArgumentException(where + " 的 id 不允许包含 '.'：" + id);
        }
        switch (type) {
            case AGENT -> {
                if (isBlank(prompt)) {
                    throw new IllegalArgumentException(where + " 是 agent 节点，必须提供 prompt");
                }
                for (String allowed : choice) {
                    if (allowed == null || allowed.isBlank()) {
                        throw new IllegalArgumentException(where + " 的 choice 里不能有空值");
                    }
                }
            }
            case TOOL -> {
                if (isBlank(tool)) {
                    throw new IllegalArgumentException(where + " 是 tool 节点，必须提供 tool 名称");
                }
            }
            case JAVA -> {
                if (isBlank(handler)) {
                    throw new IllegalArgumentException(where + " 是 java 节点，必须提供 handler 名称");
                }
            }
            case CODE -> {
                if (behavior == null) {
                    throw new IllegalArgumentException(where + " 是 code 节点，必须绑定一个 WorkflowHandler"
                            + "（代码节点只能由 JavaFlow 在 Java 里构造，不能写在 YAML/JSON 里）");
                }
            }
            case SET -> {
                if (set.isEmpty()) {
                    throw new IllegalArgumentException(where + " 是 set 节点，必须提供至少一个 set 键值");
                }
            }
            case END, START, BRANCH -> {
                // 三者都可以不带任何参数：START 透传输入，BRANCH 只看出边，END 回退到"上一节点输出"
            }
        }
        if (type != NodeType.AGENT && !choice.isEmpty()) {
            throw new IllegalArgumentException(where + " 的类型是 " + type.wireName()
                    + "，只有 agent 节点支持 choice（输出枚举契约）");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof WorkflowNode other && Objects.equals(id, other.id) && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    @Override
    public String toString() {
        return type.wireName() + ":" + id;
    }

    /** 构建器；同时作为 Jackson 的反序列化目标（{@code withPrefix=""} 即"字段名即方法名"）。 */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String id;
        private NodeType type;
        private String description;
        private String prompt;
        private String system;
        private String instruction;
        private String tool;
        private String handler;
        private WorkflowHandler behavior;
        private final Map<String, Object> args = new LinkedHashMap<>();
        private final Map<String, String> set = new LinkedHashMap<>();
        private String output;
        private int retry;
        private boolean continueOnError;
        private int maxSteps = -1;
        private final List<String> choice = new ArrayList<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(NodeType type) {
            this.type = type;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        public Builder tool(String tool) {
            this.tool = tool;
            return this;
        }

        public Builder handler(String handler) {
            this.handler = handler;
            return this;
        }

        /** 只在代码里设置；{@code @JsonIgnore} 让 Jackson 不去尝试实例化一个接口。 */
        @JsonIgnore
        public Builder behavior(WorkflowHandler behavior) {
            this.behavior = behavior;
            return this;
        }

        public Builder args(Map<String, Object> args) {
            this.args.clear();
            if (args != null) {
                this.args.putAll(args);
            }
            return this;
        }

        public Builder set(Map<String, String> set) {
            this.set.clear();
            if (set != null) {
                this.set.putAll(set);
            }
            return this;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder retry(int retry) {
            this.retry = retry;
            return this;
        }

        public Builder continueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder choice(List<String> choice) {
            this.choice.clear();
            if (choice != null) {
                this.choice.addAll(choice);
            }
            return this;
        }

        public WorkflowNode build() {
            return new WorkflowNode(this);
        }
    }
}
