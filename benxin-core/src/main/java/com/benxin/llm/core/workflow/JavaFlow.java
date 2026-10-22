package com.benxin.llm.core.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * <b>代码编排</b>：把工作流的拓扑直接用 Java 写出来，而不是写在 YAML 里。
 *
 * <p>它产出的是 {@link WorkflowDefinition}，因此和声明式工作流<b>跑的是同一个引擎</b> ——
 * 边序即优先级、单节点访问上限、节点级 {@code retry} / {@code continueOnError}、
 * 步数预算、事件与变量回写，语义完全相同。区别只在"谁来写这张图"：</p>
 *
 * <table border="1">
 *   <caption>两种写法</caption>
 *   <tr><th></th><th>YAML（{@code workflow}）</th><th>代码（{@code JavaFlow}）</th></tr>
 *   <tr><td>拓扑</td><td>配置文件</td><td>Java 代码</td></tr>
 *   <tr><td>节点行为</td><td>{@code type: java} + {@code handler} 名字</td>
 *       <td>直接绑一个 {@link WorkflowHandler} 对象</td></tr>
 *   <tr><td>边条件</td><td>{@code when: "${x} == y"} 表达式</td>
 *       <td>{@code Predicate<FlowView>} 谓词</td></tr>
 *   <tr><td>可序列化</td><td>是，可跨进程、可热改</td><td><b>否</b>，见下</td></tr>
 * </table>
 *
 * <p><b>代价是明确的</b>：图里挂着活对象，所以这类定义不可序列化、不能由配置热改、
 * 也不能跨进程传输（{@link WorkflowIo} 会明确拒绝，而不是写出一份读回来就变形的文件）。
 * 换来的是：类型安全、IDE 能重构、节点可拿去单测、可以直接注入 Spring 依赖。</p>
 *
 * <p><b>两个模式单向不混用</b>：YAML 里不能引用代码节点（{@code type: code} 会在加载期报错）；
 * 代码里可以继续用内置节点（agent / tool / branch / set / end），也可以把
 * {@code @LlmNode} 注册的处理器当普通对象传进来。</p>
 *
 * <pre>{@code
 * WorkflowDefinition flow = JavaFlow.builder("review")
 *         .start("begin", "请评审：${input}")
 *         .agent("analyze", "分析：${begin}")
 *         .code("lint", new LintNode(threshold))       // 任意对象，可注入依赖
 *         .branch("route")
 *         .edge("begin",   "analyze")
 *         .edge("analyze", "route")
 *         .edge("route",   "fix", v -> v.text("analyze").contains("严重"))
 *         .edge("route",   "report")
 *         .end("report", "结论：${analyze}\n检查：${lint}")
 *         .build();
 * }</pre>
 *
 * <p>写完之后用 {@code CodeFlowLoop.of(flow)} 跑（或者 {@code new WorkflowLoop(flow)}）。</p>
 */
public final class JavaFlow {

    private final String name;
    private final List<WorkflowNode> nodes = new ArrayList<>();
    private final List<WorkflowEdge> edges = new ArrayList<>();
    private String description;
    private String entry;
    private int maxVisitsPerNode = WorkflowDefinition.DEFAULT_MAX_VISITS;

    private JavaFlow(String name) {
        this.name = name == null || name.isBlank() ? "flow" : name.trim();
    }

    /** 开始描述一张图。 */
    public static JavaFlow builder(String name) {
        return new JavaFlow(name);
    }

    // ------------------------------------------------------------------
    // 节点
    // ------------------------------------------------------------------

    /** 入口节点：直接透传任务原文。 */
    public JavaFlow start(String id) {
        return add(WorkflowNode.builder(id).type(NodeType.START));
    }

    /** 入口节点：把 {@code prompt} 渲染成起点文本（通常写成 {@code "任务：${input}"}）。 */
    public JavaFlow start(String id, String prompt) {
        return add(WorkflowNode.builder(id).type(NodeType.START).prompt(prompt));
    }

    /** 一次完整的模型往返（内部会推进到"模型不再请求工具"为止）。 */
    public JavaFlow agent(String id, String prompt) {
        return add(WorkflowNode.builder(id).type(NodeType.AGENT).prompt(prompt));
    }

    /**
     * 系统提示词与用户提示词<b>分开写</b>：{@code system} 走真正的系统通道，
     * {@code prompt} 才是这一轮的用户输入。
     *
     * <p>系统提示词会被<b>追加</b>到 Agent 级系统提示词之后（不是替换）——
     * 身份与输出约定通常是全局的，节点级只是在这一步加一层角色约束。
     * 在 Anthropic / Gemini 上它落在顶层 {@code system} / {@code systemInstruction}，
     * 而不是混在用户消息里。</p>
     *
     * <p>典型用法是"让这一步只输出枚举值"，好让下游的
     * {@link Cases#caseOf(String, String) 精确分支}可靠：</p>
     *
     * <pre>{@code
     * .agent("judge", "只输出下面三个词之一：通过 / 驳回 / 需补充材料", "请判断：${material}")
     * }</pre>
     */
    public JavaFlow agent(String id, String system, String prompt) {
        return add(WorkflowNode.builder(id).type(NodeType.AGENT)
                .system(system).prompt(prompt));
    }

    /** 直接调一个已注册的工具，不经过模型。 */
    public JavaFlow tool(String id, String tool) {
        return add(WorkflowNode.builder(id).type(NodeType.TOOL).tool(tool));
    }

    /** 直接调工具，参数是模板（运行期做 {@code ${...}} 插值）。 */
    public JavaFlow tool(String id, String tool, Map<String, Object> args) {
        return add(WorkflowNode.builder(id).type(NodeType.TOOL).tool(tool).args(args));
    }

    /**
     * 代码节点：行为是一个直接绑在节点上的对象。
     *
     * <p>这是本模式的核心 —— 不需要注册名字，也不需要 Spring 容器：
     * {@code new ReviewNode(...)} 传进来就行。同一个对象可以被多张图复用，
     * 因此它应当是无状态（或线程安全）的。</p>
     */
    public JavaFlow code(String id, WorkflowHandler behavior) {
        return add(WorkflowNode.builder(id).type(NodeType.CODE).behavior(behavior));
    }

    /** 同上，并传入模板参数（运行期渲染后交给 {@code NodeInput.args()}）。 */
    public JavaFlow code(String id, WorkflowHandler behavior, Map<String, Object> args) {
        return add(WorkflowNode.builder(id).type(NodeType.CODE).behavior(behavior).args(args));
    }

    /** 纯路由节点：自己不产生输出，只按出边挑一条走。 */
    public JavaFlow branch(String id) {
        return add(WorkflowNode.builder(id).type(NodeType.BRANCH));
    }

    /**
     * <b>多出口分支</b>：把"按某个值分几路"写成一组具名分支，而不是散落的一串谓词。
     *
     * <pre>{@code
     * .agent("judge", "只输出：通过 / 驳回 / 需补充材料", "请判断：${material}")
     * .branchOn("route", "judge", c -> c
     *         .caseOf("通过", "approve")
     *         .caseOf("驳回", "reject")
     *         .caseOf("需补充材料", "supplement")
     *         .otherwise("approve"))          // 兜底：建议永远写一条
     * }</pre>
     *
     * <p>它只是语法糖：产出的仍是一个 {@code branch} 节点加若干条谓词边，
     * 因此<b>边序即优先级</b>与手写完全一致，引擎一行没改。分支按书写顺序求值。</p>
     *
     * <p>分支节点本身不产生输出：真正决定走向的是 {@code valueName} 指向的值
     * （可以是某个节点的输出，也可以是 {@code set} 写入的变量）。</p>
     *
     * @param id        分支节点 id（本方法会添加这个节点）
     * @param valueName 要判断的变量名 / 节点 id
     * @param cases     分支列表；为空时退化为一个普通 branch 节点
     */
    public JavaFlow branchOn(String id, String valueName, Consumer<Cases> cases) {
        if (valueName == null || valueName.isBlank()) {
            throw new IllegalArgumentException("branchOn 需要指定要判断的值（valueName），例如某个节点的 id");
        }
        branch(id);
        if (cases != null) {
            cases.accept(new Cases(id, valueName));
        }
        return this;
    }

    /**
     * {@link #branchOn} 的分支描述器。
     *
     * <p>提供两种匹配：{@link #caseOf} 是<b>精确匹配</b>（去首尾空白后相等），
     * {@link #whenContains} 是<b>子串匹配</b>。默认建议用精确匹配 ——
     * 它要求上一步"只输出枚举值"，而这正好可以用节点级系统提示词做到
     * （见 {@link #agent(String, String, String)}）。</p>
     */
    public final class Cases {

        private final String from;
        private final String valueName;

        private Cases(String from, String valueName) {
            this.from = from;
            this.valueName = valueName;
        }

        /**
         * 精确匹配：{@code 值.trim().equals(expected)}。
         *
         * <p>比子串匹配可靠：{@code 通过} 不会误命中 {@code 不通过}。</p>
         */
        public Cases caseOf(String expected, String target) {
            String key = requireText(expected, "caseOf 的匹配值");
            edge(from, target, v -> v.text(valueName).trim().equals(key));
            return this;
        }

        /**
         * 子串匹配：{@code 值.contains(expected)}。
         *
         * <p>适合模型输出带前后缀（"结论：通过"）的场景，但要注意包含关系：
         * 判断 {@code 通过} 会连 {@code 不通过} 一起命中。要避免这个坑，
         * 请让上一步只输出枚举值并改用 {@link #caseOf}。</p>
         */
        public Cases whenContains(String expected, String target) {
            String key = requireText(expected, "whenContains 的匹配值");
            edge(from, target, v -> v.text(valueName).contains(key));
            return this;
        }

        /**
         * 兜底分支（无条件边）。
         *
         * <p>建议永远写一条：没有兜底时，一旦值不属于任何分支，
         * 引擎会按"死胡同"结束整张图（并打一条 warn），而不是悄悄走错分支。</p>
         */
        public JavaFlow otherwise(String target) {
            edge(from, target);
            return JavaFlow.this;
        }

        private String requireText(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(what + " 不能为空");
            }
            return value;
        }
    }

    /** 写变量：值是模板，运行期渲染后写进黑板。 */
    public JavaFlow set(String id, Map<String, String> values) {
        return add(WorkflowNode.builder(id).type(NodeType.SET).set(values));
    }

    /** 终点：用最后一个输出作为最终答案。 */
    public JavaFlow end(String id) {
        return add(WorkflowNode.builder(id).type(NodeType.END));
    }

    /** 终点：渲染 {@code output} 作为最终答案。 */
    public JavaFlow end(String id, String output) {
        return add(WorkflowNode.builder(id).type(NodeType.END).output(output));
    }

    /**
     * 通用入口：需要 {@code retry} / {@code continueOnError} / {@code maxSteps} 等
     * 逐节点配置时用它，避免为每种节点再堆一组重载。
     *
     * <pre>{@code
     * .node("fetch", NodeType.AGENT, b -> b.prompt("抓取").retry(2).continueOnError(true))
     * }</pre>
     */
    public JavaFlow node(String id, NodeType type, Consumer<WorkflowNode.Builder> customizer) {
        WorkflowNode.Builder builder = WorkflowNode.builder(id).type(type);
        if (customizer != null) {
            customizer.accept(builder);
        }
        return add(builder);
    }

    /** 逃生口：直接塞一个已构造好的节点。 */
    public JavaFlow node(WorkflowNode node) {
        if (node != null) {
            nodes.add(node);
        }
        return this;
    }

    // ------------------------------------------------------------------
    // 边
    // ------------------------------------------------------------------

    /** 无条件边（默认分支）。 */
    public JavaFlow edge(String from, String to) {
        return edge(WorkflowEdge.of(from, to));
    }

    /**
     * 带谓词条件的边。
     *
     * <p>顺序即优先级：同一节点的多条出边按声明顺序求值，取第一条成立的。
     * 默认边请写在最后一条。</p>
     */
    public JavaFlow edge(String from, String to, Predicate<FlowView> condition) {
        return edge(WorkflowEdge.when(from, to, condition));
    }

    /** 逃生口：直接塞一条已构造好的边（例如用表达式 {@code when} 的边）。 */
    public JavaFlow edge(WorkflowEdge edge) {
        if (edge != null) {
            edges.add(edge);
        }
        return this;
    }

    /**
     * 串起一串无条件边：{@code chain("a","b","c")} 等价于 {@code a→b}、{@code b→c}。
     *
     * <p>线性流程用它可以少写几行，但别用它绕开"为什么这里要无条件直连"这个问题。</p>
     */
    public JavaFlow chain(String... ids) {
        if (ids != null) {
            for (int i = 0; i + 1 < ids.length; i++) {
                edge(ids[i], ids[i + 1]);
            }
        }
        return this;
    }

    // ------------------------------------------------------------------
    // 图级属性
    // ------------------------------------------------------------------

    public JavaFlow description(String description) {
        this.description = description;
        return this;
    }

    /** 显式指定入口节点；不指定时由引擎按 start 节点 / 无入边节点推断。 */
    public JavaFlow entry(String entry) {
        this.entry = entry;
        return this;
    }

    /** 单节点访问上限（防"不调模型的死循环"）；默认沿用 {@link WorkflowDefinition} 的值。 */
    public JavaFlow maxVisitsPerNode(int maxVisitsPerNode) {
        this.maxVisitsPerNode = maxVisitsPerNode;
        return this;
    }

    // ------------------------------------------------------------------

    /** 产出图定义；校验规则与 YAML 路径完全一致。 */
    public WorkflowDefinition build() {
        return WorkflowDefinition.builder(name)
                .description(description)
                .entry(entry)
                .nodes(List.copyOf(nodes))
                .edges(List.copyOf(edges))
                .maxVisitsPerNode(maxVisitsPerNode)
                .build();
    }

    /** 当前已添加的节点数（便于测试与排错）。 */
    public int nodeCount() {
        return nodes.size();
    }

    /** 当前已添加的边数。 */
    public int edgeCount() {
        return edges.size();
    }

    private JavaFlow add(WorkflowNode.Builder builder) {
        nodes.add(builder.build());
        return this;
    }

    /** 便于把一组变量按声明顺序放进 {@code set} / {@code args}。 */
    public static Map<String, String> vars(Object... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        }
        return map;
    }

    /** 便于把一组参数按声明顺序放进 {@code tool} / {@code code}。 */
    public static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    @Override
    public String toString() {
        return "JavaFlow[" + name + "] " + nodes.size() + " 节点 / " + edges.size() + " 边";
    }
}
