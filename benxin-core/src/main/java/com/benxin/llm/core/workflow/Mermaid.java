package com.benxin.llm.core.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一张 {@link WorkflowDefinition} 导出成 <b>Mermaid flowchart</b>。
 *
 * <p>代码编排最大的可读性问题是"线性读代码看不见图"。这个导出器让两件事变得可行：</p>
 * <ul>
 *   <li><b>写之前画图</b>：先有 Mermaid，再照着接代码；</li>
 *   <li><b>写完对图</b>：把代码里的图导出来，与设计稿逐行比对 —— 多一条边、少一个终点，
 *       肉眼就能发现，而不是等跑挂了才想"是不是少接了一条"。</li>
 * </ul>
 *
 * <p>输出刻意保持稳定（节点按定义顺序、边按声明顺序），因此可以当快照断言：
 * 图结构改动会直接反映在字符串 diff 里。</p>
 *
 * <p>边上的标签优先级：{@link WorkflowEdge#label()}（{@code caseOf} 会自动写）
 * → {@link WorkflowEdge#when()} 表达式 → {@code λ}（谓词，无法字符串化）。</p>
 */
public final class Mermaid {

    /** 节点标签的最大长度，超过就截断 —— 图是给人看的，不是文档。 */
    private static final int LABEL_LIMIT = 24;

    private Mermaid() {
    }

    /** 导出为 {@code flowchart TD} 文本。 */
    public static String toMermaid(WorkflowDefinition definition) {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        if (definition.description() != null && !definition.description().isBlank()) {
            sb.append("    %% ").append(escape(definition.description())).append('\n');
        }
        for (WorkflowNode node : definition.nodes()) {
            sb.append("    ").append(node.id()).append(shape(node)).append('\n');
        }
        List<String> edgeLines = new ArrayList<>();
        for (WorkflowEdge edge : definition.edges()) {
            String label = edgeLabel(edge);
            String line = label == null
                    ? edge.from() + " --> " + edge.to()
                    : edge.from() + " -->|\"" + escape(label) + "\"| " + edge.to();
            edgeLines.add(line);
        }
        edgeLines.forEach(line -> sb.append("    ").append(line).append('\n'));
        return sb.toString();
    }

    /** 节点形状：开始/结束用胶囊、判断用菱形、其余用矩形（与手写 Mermaid 的习惯一致）。 */
    private static String shape(WorkflowNode node) {
        String label = escape(nodeLabel(node));
        return switch (node.type()) {
            case START, END -> "([\"" + label + "\"])";
            case BRANCH -> "{\"" + label + "\"}";
            default -> "[\"" + label + "\"]";
        };
    }

    /** 节点显示文本：描述 > 提示词/输出首行 > 工具/处理器 > 兜底。 */
    private static String nodeLabel(WorkflowNode node) {
        if (notBlank(node.description())) {
            return abbreviate(node.description());
        }
        if (notBlank(node.prompt())) {
            return abbreviate(firstLine(node.prompt()));
        }
        if (notBlank(node.output())) {
            return abbreviate(firstLine(node.output()));
        }
        if (notBlank(node.tool())) {
            return abbreviate("工具: " + node.tool());
        }
        if (notBlank(node.handler())) {
            return abbreviate("java: " + node.handler());
        }
        if (node.behavior() != null) {
            return abbreviate("code: " + node.id());
        }
        return abbreviate(node.type().wireName() + ": " + node.id());
    }

    private static String edgeLabel(WorkflowEdge edge) {
        if (notBlank(edge.label())) {
            return edge.label();
        }
        if (notBlank(edge.when())) {
            return edge.when();
        }
        return edge.condition() != null ? "λ" : null;
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.trim();
    }

    private static String abbreviate(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= LABEL_LIMIT ? compact : compact.substring(0, LABEL_LIMIT) + "…";
    }

    /** Mermaid 里会破坏语法的字符：引号与竖线换掉；花括号在带引号的标签里是安全的，保留。 */
    private static String escape(String text) {
        return text.replace("\"", "'")
                .replace("|", "/")
                .replace("\n", " ");
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
