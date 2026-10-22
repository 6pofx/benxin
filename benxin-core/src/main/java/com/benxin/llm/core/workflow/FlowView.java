package com.benxin.llm.core.workflow;

/**
 * 边条件在 Java 编排里的只读视图。
 *
 * <p>YAML 工作流用 {@code when: "${verdict} == 通过"} 这样的表达式小语言写条件；
 * 代码编排则直接给一个 {@code Predicate<FlowView>} —— 类型安全、IDE 能重构、
 * 写错编译不过。这个接口就是谓词能看到的那一面黑板。</p>
 *
 * <p>与 {@link NodeInput} 的区别：{@code NodeInput} 是节点执行前的<b>快照</b>，
 * 而这里的实现是<b>活视图</b>（每次取值都读当前黑板）。谓词在选边那一刻求值，
 * 需要看到节点刚写进去的东西，因此不能做成快照。</p>
 *
 * <p>只暴露读操作：条件不该有副作用。要改状态，请在节点里改。</p>
 */
public interface FlowView {

    /** 本次任务的原文。 */
    String input();

    /** 上一个产生输出的节点的输出。 */
    String last();

    /** 最近一次失败的说明；没有失败时返回空串。 */
    String error();

    /** 某个变量或节点输出是否存在（{@code null} 也算不存在）。 */
    boolean has(String name);

    /** 某个变量或节点输出的字符串形式；不存在返回空串。 */
    String text(String name);

    /** 某个变量或节点输出的原始值（可能是数字、布尔、List、Map）；不存在返回 {@code null}。 */
    Object raw(String name);

    /**
     * 把黑板包成一个只读视图。
     *
     * <p>刻意做成静态工厂而不是让 {@link WorkflowState} 直接实现本接口：
     * 黑板是引擎的内部状态，不该因为"条件要用"就多出一批公开的读方法。</p>
     */
    static FlowView of(WorkflowState state) {
        if (state == null) {
            throw new IllegalArgumentException("FlowView 不能基于 null 黑板创建");
        }
        return new FlowView() {
            @Override
            public String input() {
                return state.input();
            }

            @Override
            public String last() {
                return state.last();
            }

            @Override
            public String error() {
                return state.error() == null ? "" : state.error();
            }

            @Override
            public boolean has(String name) {
                if (name == null || name.isBlank()) {
                    return false;
                }
                // 内置量走 state 的专用访问器，其余查变量表（与模板取值同一口径）
                Object value = state.lookup().apply(name);
                return value != null;
            }

            @Override
            public String text(String name) {
                String text = Templates.stringify(state.lookup().apply(name));
                return text == null ? "" : text;
            }

            @Override
            public Object raw(String name) {
                return name == null ? null : state.lookup().apply(name);
            }
        };
    }
}
