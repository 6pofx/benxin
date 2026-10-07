package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 一张工作流图：节点 + 有向边。
 *
 * <p>这是"声明式工作流模式"的领域模型。它刻意是<b>纯数据</b>：不持有任何模型、工具或
 * 运行期状态，因此可以自由地从 YAML 读进来、序列化回去、被断言、被缓存，也可以脱离
 * Spring 与 Agent 单独做静态分析（例如"哪些节点引用了不存在的变量"）。</p>
 *
 * <p>执行它的唯一入口是 {@link WorkflowEngine}；接入 Agent 循环的是
 * {@code WorkflowLoop}（在 {@code core/loop} 包，负责接入 Agent 运行时）。
 * 三者的关系与 core 里其它部分一致：<b>模型 / 引擎 / 适配层</b>分离。</p>
 *
 * <p>入口节点的确定顺序：显式 {@code entry} → 类型为 {@code start} 的节点 →
 * 第一个没有入边的节点 → 第一个节点。前两条是"我说了算"，后两条是"图自己说了算"，
 * 于是简单工作流可以完全省略 {@code entry}。</p>
 */
@JsonDeserialize(builder = WorkflowDefinition.Builder.class)
public final class WorkflowDefinition {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinition.class);

    /** 单节点默认访问上限，用来给"不调模型的死循环"（branch ↔ set）兜底。 */
    public static final int DEFAULT_MAX_VISITS = 100;

    private final String name;
    private final String description;
    private final String entry;
    private final List<WorkflowNode> nodes;
    private final List<WorkflowEdge> edges;
    private final int maxVisitsPerNode;
    private final Map<String, WorkflowNode> byId;
    private final Map<String, List<WorkflowEdge>> outgoing;

    private WorkflowDefinition(Builder b) {
        this.name = b.name == null || b.name.isBlank() ? "workflow" : b.name.trim();
        this.description = b.description;
        this.entry = b.entry == null || b.entry.isBlank() ? null : b.entry.trim();
        this.nodes = List.copyOf(b.nodes);
        this.edges = List.copyOf(b.edges);
        this.maxVisitsPerNode = b.maxVisitsPerNode <= 0 ? DEFAULT_MAX_VISITS : b.maxVisitsPerNode;

        Map<String, WorkflowNode> index = new LinkedHashMap<>();
        Map<String, List<WorkflowEdge>> out = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            index.put(node.id(), node);
            out.put(node.id(), new ArrayList<>());
        }
        for (WorkflowEdge edge : edges) {
            List<WorkflowEdge> list = out.get(edge.from());
            if (list != null) {
                list.add(edge);
            }
        }
        this.byId = Collections.unmodifiableMap(index);
        Map<String, List<WorkflowEdge>> frozen = new LinkedHashMap<>();
        out.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        this.outgoing = Collections.unmodifiableMap(frozen);
    }

    public static Builder builder(String name) {
        return new Builder().name(name);
    }

    /** 便捷构造：自动按"无入边"推断入口。 */
    public static WorkflowDefinition of(String name, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return builder(name).nodes(nodes).edges(edges).build();
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** 显式指定的入口节点 id；未指定时为 {@code null}。 */
    public String entry() {
        return entry;
    }

    public List<WorkflowNode> nodes() {
        return nodes;
    }

    public List<WorkflowEdge> edges() {
        return edges;
    }

    public int maxVisitsPerNode() {
        return maxVisitsPerNode;
    }

    public Set<String> nodeIds() {
        return byId.keySet();
    }

    public Optional<WorkflowNode> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public WorkflowNode require(String id) {
        WorkflowNode node = id == null ? null : byId.get(id);
        if (node == null) {
            throw new IllegalArgumentException("工作流 [" + name + "] 中不存在节点 [" + id + "]，"
                    + "已有节点: " + byId.keySet());
        }
        return node;
    }

    /** 某节点的出边，按声明顺序（即优先级）返回。 */
    public List<WorkflowEdge> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    /** 以 {@code to} 为终点的入边。 */
    public List<WorkflowEdge> incoming(String nodeId) {
        List<WorkflowEdge> result = new ArrayList<>();
        for (WorkflowEdge edge : edges) {
            if (edge.to().equals(nodeId)) {
                result.add(edge);
            }
        }
        return result;
    }

    /** 解析入口节点；无法解析时抛异常（此时图本身有问题）。 */
    public WorkflowNode resolveEntry() {
        if (entry != null) {
            return require(entry);
        }
        for (WorkflowNode node : nodes) {
            if (node.type() == NodeType.START) {
                return node;
            }
        }
        Set<String> hasIncoming = new LinkedHashSet<>();
        for (WorkflowEdge edge : edges) {
            hasIncoming.add(edge.to());
        }
        for (WorkflowNode node : nodes) {
            if (!hasIncoming.contains(node.id())) {
                return node;
            }
        }
        // 全是环：退化为第一个节点，并由引擎的访问上限负责兜底
        return nodes.get(0);
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /**
     * 静态校验。在 {@link Builder#build()} 里自动调用，因此"建出来的图一定是合法的"。
     *
     * <p>顺序上有意先查结构性错误（缺 id、重复 id、边指向不存在的节点），再查语法错误
     * （条件表达式），这样报出来的第一条永远是最该先修的那个。</p>
     */
    public void validate() {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("工作流 [" + name + "] 没有任何节点");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (WorkflowNode node : nodes) {
            node.validate("工作流 [" + name + "] 的节点");
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("工作流 [" + name + "] 里节点 id 重复：" + node.id());
            }
            if (WorkflowState.RESERVED.contains(node.id())) {
                throw new IllegalArgumentException("工作流 [" + name + "] 的节点 id [" + node.id()
                        + "] 与内置变量重名，请改名（保留名: " + WorkflowState.RESERVED + "）");
            }
        }
        for (WorkflowEdge edge : edges) {
            edge.validate("工作流 [" + name + "]");
            require(edge.from());
            require(edge.to());
            Conditions.validate(edge.when());
        }
        if (entry != null) {
            require(entry);
        }
        if (maxVisitsPerNode <= 0) {
            throw new IllegalArgumentException("工作流 [" + name + "] 的 maxVisitsPerNode 必须为正数");
        }
        warnUnreachableEdges();
        warnEdgesLeavingEnd();
    }

    /**
     * 加载期提示：END 节点的出边永远不会被执行。
     *
     * <p>引擎在 END 处结束推进（{@code case END ->} 只负责产出最终文本），从不求值它的出边。
     * 于是 {@code end → 后续节点} 是纯死代码：定义能加载、能跑完、不报错、不警告，
     * 只是后半张图从来没被执行过 —— 与"默认边之前还有条件边"同口径，这里给一条 WARN。</p>
     */
    private void warnEdgesLeavingEnd() {
        for (WorkflowNode node : nodes) {
            if (node.type() != NodeType.END) {
                continue;
            }
            List<WorkflowEdge> out = outgoing(node.id());
            if (!out.isEmpty()) {
                log.warn("[workflow:{}] END 节点 [{}] 还有 {} 条出边 {}，永远不会执行："
                                + "引擎在 END 处结束推进、END 只产出最终文本。"
                                + "若本意是继续流转，请把该节点的 type 改成 set 或普通节点",
                        name, node.id(), out.size(), out);
            }
        }
    }

    /**
     * 加载期可达性提示：无条件的出边之后，所有条件出边都是死边。
     *
     * <p>引擎按声明顺序取第一条条件成立的边走，因此"默认边放最后"是唯一需要记住的规则 ——
     * 而这条规则最容易违反，后果又最难在运行时发现：图能跑完、结果看着像样，
     * 只是某个分支从来没被走过。本类对"节点引用不存在""条件语法错误""location 与 inline 同时配置"
     * 这类静态错误都会在加载期直接让启动失败，唯独"不可达的边"以前没有任何防线；
     * 这里给出警告（而不是抛异常）——因为把默认边写在前面的图在语义上仍然是确定的，
     * 只是与作者的本意多半不符。</p>
     */
    private void warnUnreachableEdges() {
        for (WorkflowNode node : nodes) {
            List<WorkflowEdge> out = outgoing(node.id());
            int defaultIndex = -1;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).unconditional()) {
                    defaultIndex = i;
                    break;
                }
            }
            if (defaultIndex < 0) {
                continue;
            }
            for (int i = defaultIndex + 1; i < out.size(); i++) {
                WorkflowEdge dead = out.get(i);
                log.warn("[workflow:{}] 节点 [{}] 的第 {} 条出边 {} 永远不可达："
                                + "它前面第 {} 条是不带 when 的默认边（边序即优先级）。"
                                + "若非本意，请把默认边移到所有条件边之后",
                        name, node.id(), i + 1, dead, defaultIndex + 1);
            }
        }
    }

    @Override
    public String toString() {
        return "Workflow[" + name + "] " + nodes.size() + " 节点 / " + edges.size() + " 边";
    }

    /** 构建器；同时作为 Jackson 的反序列化目标。 */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String name;
        private String description;
        private String entry;
        private final List<WorkflowNode> nodes = new ArrayList<>();
        private final List<WorkflowEdge> edges = new ArrayList<>();
        private int maxVisitsPerNode = DEFAULT_MAX_VISITS;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder entry(String entry) {
            this.entry = entry;
            return this;
        }

        public Builder nodes(List<WorkflowNode> nodes) {
            this.nodes.clear();
            if (nodes != null) {
                this.nodes.addAll(nodes);
            }
            return this;
        }

        public Builder node(WorkflowNode node) {
            if (node != null) {
                this.nodes.add(node);
            }
            return this;
        }

        public Builder edges(List<WorkflowEdge> edges) {
            this.edges.clear();
            if (edges != null) {
                this.edges.addAll(edges);
            }
            return this;
        }

        public Builder edge(WorkflowEdge edge) {
            if (edge != null) {
                this.edges.add(edge);
            }
            return this;
        }

        /** 便捷：{@code from} 无条件指向 {@code to}。 */
        public Builder edge(String from, String to) {
            return edge(WorkflowEdge.of(from, to));
        }

        /** 便捷：{@code from} 在条件成立时指向 {@code to}。 */
        public Builder edge(String from, String to, String when) {
            return edge(WorkflowEdge.when(from, to, when));
        }

        public Builder maxVisitsPerNode(int maxVisitsPerNode) {
            this.maxVisitsPerNode = maxVisitsPerNode;
            return this;
        }

        public WorkflowDefinition build() {
            WorkflowDefinition definition = new WorkflowDefinition(this);
            definition.validate();
            return definition;
        }
    }
}
