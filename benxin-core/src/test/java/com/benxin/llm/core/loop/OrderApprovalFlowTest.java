package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.core.workflow.JavaFlow;
import com.benxin.llm.core.workflow.Mermaid;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一张真实的<b>订单审核流程图</b>，用 {@link JavaFlow} 原样编码。
 *
 * <p>原图（Mermaid）里除了 {@code D -. 并行通知 .-> T} 那条并行边之外，全部节点与边都在这里 ——
 * 并行扇出引擎目前不支持（每次只走一条出边），那条支线的三个节点 T/U/V 因此被略去。</p>
 *
 * <p><b>可读性靠两件事，而不是靠注释堆</b>：</p>
 * <ol>
 *   <li><b>分段</b>：整张图不是一条 70 行的链，而是 {@link #intake}、{@link #riskControl}、
 *       {@link #rework}、{@link #approval}、{@link #timeout} 五个方法，
 *       方法名就是这段图在做什么；{@link #orderApproval()} 本身只有六行。</li>
 *   <li><b>可反查</b>：{@link Mermaid#toMermaid} 能把代码里的图导回 Mermaid，
 *       与设计稿逐行比对 —— 见 {@link #exportsMermaidGraph()}。</li>
 * </ol>
 *
 * <p>两个审核结论（F、M）用 {@code choice} 声明<b>输出契约</b>：模型只能吐"通过/驳回/需补充材料"，
 * 归一化判不出来就让节点失败。因此下游 {@code caseOf} 的精确匹配是有保障的，
 * 而不是"希望模型照做"。</p>
 */
class OrderApprovalFlowTest {

    /** 金额阈值：超过就要转高级审核员复核。 */
    private static final int AMOUNT_THRESHOLD = 5_000;

    // ------------------------------------------------------------------
    // 图：先分段，再拼起来
    // ------------------------------------------------------------------

    static WorkflowDefinition orderApproval() {
        JavaFlow flow = JavaFlow.builder("order-approval")
                .description("订单审核：基础校验 → 风控 → 审核结论 → 金额分级 → 高级复核 → 履约/驳回/超时");

        flow = intake(flow);
        flow = riskControl(flow);
        flow = rework(flow);
        flow = approval(flow);
        return timeout(flow).build();
    }

    /** A 开始 → B 收单 → C 基础校验；不通过就记录原因并关闭。 */
    private static JavaFlow intake(JavaFlow flow) {
        return flow.start("A", "订单数据：${input}")
                .code("B", OrderApprovalFlowTest::receiveOrder).describe("B", "系统接收订单")
                .chain("A", "B")
                .branchOn("C", "valid", c -> c.caseOf("true", "D").otherwise("C1"))
                .chain("B", "C")
                .code("C1", in -> NodeOutput.of("记录校验失败原因")).describe("C1", "记录校验失败原因")
                .end("C2", "【校验失败关闭】${C1}")
                .chain("C1", "C2");
    }

    /** D 进审核队列 → E 风控命中判定 → F 风控结论（三出口）；驳回则发通知并结束。 */
    private static JavaFlow riskControl(JavaFlow flow) {
        return flow.code("D", in -> NodeOutput.of("进入审核队列", JavaFlow.args("status", "审核中")))
                .describe("D", "进入审核队列")
                .branchOn("E", "riskHit", c -> c.caseOf("true", "E1").otherwise("E2"))
                .chain("D", "E")
                .code("E1", in -> NodeOutput.of("转人工风控审核")).describe("E1", "转人工风控审核")
                .code("E2", in -> NodeOutput.of("进入自动审核")).describe("E2", "进入自动审核")
                .chain("E1", "F")
                .chain("E2", "F")
                // 输出契约：模型只能说这三个词之一，判不出来就纠正重问，重试用尽则节点失败
                .agent("F", "你是风控审核员", "请给出风控审核结论：\n${last}",
                        "通过", "驳回", "需补充材料")
                .describe("F", "风控审核结论")
                .routeFrom("F", "F", c -> c
                        .caseOf("通过", "G")
                        .caseOf("驳回", "H")
                        .caseOf("需补充材料", "I"))
                .code("H", in -> NodeOutput.of("生成驳回通知")).describe("H", "生成驳回通知")
                .end("H1", "【驳回结束】${H}")
                .chain("H", "H1");
    }

    /** 两条回流：补充材料超时、退回修改未重提，都落到 J（超时）；补齐/重提则回到 D 重跑。 */
    private static JavaFlow rework(JavaFlow flow) {
        return flow.code("I", in -> NodeOutput.of("通知商家补充材料")).describe("I", "通知商家补充材料")
                .chain("I", "I1")
                .branchOn("I1", "supplemented", c -> c.caseOf("true", "D").otherwise("J"))
                .code("N", in -> NodeOutput.of("退回商家修改订单")).describe("N", "退回商家修改订单")
                .chain("N", "N1")
                .branchOn("N1", "resubmitted", c -> c.caseOf("true", "D").otherwise("J"));
    }

    /** G 金额分级 →（超阈值）L 高级复核 → M 复核结论（三出口）→ K 通过 → 履约。 */
    private static JavaFlow approval(JavaFlow flow) {
        return flow.branchOn("G", "overThreshold", c -> c.caseOf("true", "L").otherwise("K"))
                .code("L", in -> NodeOutput.of("转高级审核员复核")).describe("L", "转高级审核员复核")
                .chain("L", "M")
                .agent("M", "你是高级审核员", "请给出高级复核结论：\n${last}",
                        "通过", "驳回", "退回修改")
                .describe("M", "高级复核结论")
                .routeFrom("M", "M", c -> c
                        .caseOf("通过", "K")
                        .caseOf("驳回", "H")
                        .caseOf("退回修改", "N"))
                .code("K", in -> NodeOutput.of("自动审核通过", JavaFlow.args("status", "已通过")))
                .describe("K", "自动审核通过")
                .chain("K", "O")
                .code("O", in -> NodeOutput.of("生成审核通过通知")).describe("O", "生成审核通过通知")
                .chain("O", "P")
                .code("P", in -> NodeOutput.of("进入履约/发货流程")).describe("P", "进入履约/发货流程")
                .end("Q", "【审核通过】${O}｜${P}")
                .chain("P", "Q");
    }

    /** J 超时计时 → R 通知 → S 结束。 */
    private static JavaFlow timeout(JavaFlow flow) {
        return flow.code("J", in -> NodeOutput.of("触发超时计时器")).describe("J", "触发超时计时器")
                .code("R", in -> NodeOutput.of("发送超时关闭通知")).describe("R", "发送超时关闭通知")
                .end("S", "【超时关闭】${R}")
                .chain("J", "R")
                .chain("R", "S");
    }

    /**
     * B：把订单 JSON 解析成判定变量。
     *
     * <p>真实系统里这一步是查库/查风控，这里简化成解析入参，好让整条路径可复现。</p>
     */
    private static NodeOutput receiveOrder(NodeInput in) {
        JsonNode order = Json.parseQuietly(in.input());
        boolean valid = order == null || order.path("valid").asBoolean(true);
        boolean riskHit = order != null && order.path("riskHit").asBoolean(false);
        int amount = order == null ? 0 : order.path("amount").asInt(0);

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("valid", valid);
        vars.put("riskHit", riskHit);
        vars.put("amount", amount);
        vars.put("overThreshold", amount > AMOUNT_THRESHOLD);
        vars.put("supplemented", order != null && order.path("supplemented").asBoolean(false));
        vars.put("resubmitted", order != null && order.path("resubmitted").asBoolean(false));
        return NodeOutput.of("系统接收订单", vars);
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    private static Agent agent(ScriptedModel model) {
        return Agent.builder("order-approval-agent")
                .model(model)
                .loop(CodeFlowLoop.of(orderApproval()))
                .maxSteps(24)
                .build();
    }

    private static List<String> visited(AgentResult result) {
        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        return run.visited();
    }

    @Test
    @DisplayName("① 基础校验不通过：一次模型都不调，直接在『校验失败关闭』收尾")
    void closesAtBasicValidation() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("（不该被调用）"));

        AgentResult result = agent(model).call("{\"valid\": false}");

        assertThat(model.callCount()).isZero();
        assertThat(visited(result)).containsExactly("A", "B", "C", "C1", "C2");
        assertThat(result.text()).isEqualTo("【校验失败关闭】记录校验失败原因");
    }

    @Test
    @DisplayName("② 命中风控且审核驳回：走人工风控 → F 驳回 → 生成通知 → 驳回结束")
    void endsWithRejection() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("驳回"));

        AgentResult result = agent(model).call("{\"valid\":true,\"riskHit\":true}");

        assertThat(visited(result))
                .containsExactly("A", "B", "C", "D", "E", "E1", "F", "H", "H1");
        assertThat(result.text()).isEqualTo("【驳回结束】生成驳回通知");
    }

    @Test
    @DisplayName("③ 需补充材料 → 回到审核队列 → 二次通过 → 金额超阈 → 高级复核通过 → 履约")
    void loopsBackAfterMaterialSupplement() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("需补充材料"),   // 第一轮 F
                ScriptedModel.text("通过"),        // 第二轮 F
                ScriptedModel.text("通过"));       // M

        AgentResult result = agent(model)
                .call("{\"valid\":true,\"riskHit\":false,\"amount\":8000,\"supplemented\":true}");

        assertThat(model.callCount()).isEqualTo(3);
        assertThat(visited(result)).containsExactly(
                "A", "B", "C", "D", "E", "E2", "F", "I", "I1",
                "D", "E", "E2", "F", "G", "L", "M", "K", "O", "P", "Q");
        assertThat(result.text()).isEqualTo("【审核通过】生成审核通过通知｜进入履约/发货流程");
        // 20 个节点跑下来，每次模型请求都只有一条用户消息：上下文没有随图增长
        assertThat(model.requests()).allSatisfy(request -> assertThat(request.messages())
                .filteredOn(m -> m.role() == com.benxin.llm.core.message.Role.USER)
                .hasSize(1));
    }

    @Test
    @DisplayName("④ 高级复核退回修改、商家未重提：走超时计时器 → 超时关闭")
    void endsWithTimeoutWhenNotResubmitted() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("通过"),        // F
                ScriptedModel.text("退回修改"));   // M

        AgentResult result = agent(model)
                .call("{\"valid\":true,\"riskHit\":false,\"amount\":8000,\"resubmitted\":false}");

        assertThat(visited(result)).containsExactly(
                "A", "B", "C", "D", "E", "E2", "F", "G", "L", "M", "N", "N1", "J", "R", "S");
        assertThat(result.text()).isEqualTo("【超时关闭】发送超时关闭通知");
    }

    @Test
    @DisplayName("⑤ 商家修改后重新提交：回到审核队列重跑，第二次通过")
    void loopsBackAfterResubmission() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("通过"),        // F
                ScriptedModel.text("退回修改"),   // M
                ScriptedModel.text("通过"),        // 回边后的 F
                ScriptedModel.text("通过"));       // 回边后的 M

        AgentResult result = agent(model)
                .call("{\"valid\":true,\"riskHit\":false,\"amount\":8000,\"resubmitted\":true}");

        assertThat(visited(result)).containsExactly(
                "A", "B", "C", "D", "E", "E2", "F", "G", "L", "M", "N", "N1",
                "D", "E", "E2", "F", "G", "L", "M", "K", "O", "P", "Q");
        assertThat(result.text()).contains("审核通过");
    }

    @Test
    @DisplayName("⑥ 模型不听话也不会走错路：输出带 Markdown 与客气话，仍被归一化成『通过』")
    void contractAbsorbsUnrulyModelOutput() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("好的，我的**结论：通过**。"));

        AgentResult result = agent(model)
                .call("{\"valid\":true,\"riskHit\":false,\"amount\":100}");

        // 归一化后进 G → 金额未超阈值 → K → 履约（而不是因为"多说了两句"掉进默认分支）
        assertThat(visited(result)).containsExactly(
                "A", "B", "C", "D", "E", "E2", "F", "G", "K", "O", "P", "Q");
        assertThat(result.text()).contains("审核通过");
    }

    // ------------------------------------------------------------------
    // 结构：代码与图互相校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("图结构与原图一致：26 个节点、31 条边、四个终点，并行支线 T/U/V 被略去")
    void keepsTheWholeGraph() {
        WorkflowDefinition flow = orderApproval();

        assertThat(flow.nodeIds()).containsExactlyInAnyOrder(
                "A", "B", "C", "C1", "C2", "D", "E", "E1", "E2", "F", "G", "H", "H1",
                "I", "I1", "J", "K", "L", "M", "N", "N1", "O", "P", "Q", "R", "S");
        // 原图里 D -. 并行通知 .-> T 这条支线不支持，也不该被悄悄造出来
        assertThat(flow.nodeIds()).doesNotContain("T", "U", "V");
        assertThat(flow.edges()).hasSize(31);
    }

    @Test
    @DisplayName("导出的 Mermaid 与设计稿同构：形状、分支标签、两条回边、四个终点都在")
    void exportsMermaidGraph() {
        String mermaid = Mermaid.toMermaid(orderApproval());

        assertThat(mermaid).startsWith("flowchart TD");
        // 开始/结束是胶囊、判断是菱形、动作是矩形
        assertThat(mermaid).contains("A([\"订单数据：${input}\"])");
        assertThat(mermaid).contains("C{\"判断：valid\"}");
        assertThat(mermaid).contains("C1[\"记录校验失败原因\"]");
        // 三出口的标签来自 caseOf，不需要人工再标注
        assertThat(mermaid).contains("F -->|\"通过\"| G")
                .contains("F -->|\"驳回\"| H")
                .contains("F -->|\"需补充材料\"| I");
        // 两条回边
        assertThat(mermaid).contains("I1 -->|\"true\"| D")
                .contains("N1 -->|\"true\"| D");
        // 四个终点
        assertThat(mermaid).contains("C2([\"【校验失败关闭】")
                .contains("H1([\"【驳回结束】")
                .contains("Q([\"【审核通过】")
                .contains("S([\"【超时关闭】");
    }
}
