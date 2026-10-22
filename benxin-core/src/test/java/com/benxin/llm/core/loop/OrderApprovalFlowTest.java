package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.core.workflow.JavaFlow;
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
 * 并行扇出引擎目前不支持（每次只走一条出边），那条支线的三个节点 T/U/V 因此被略去，
 * 这正是"忽略并行路径"的字面含义。</p>
 *
 * <p>这张图刻意覆盖了代码编排里最容易写歪的几种结构：</p>
 * <ul>
 *   <li><b>三出口判定</b>：风控审核结论、高级复核结论各三个走向；</li>
 *   <li><b>数据判定分支</b>：基础校验、风控命中、金额阈值、是否补充、是否重提；</li>
 *   <li><b>两条回边</b>：补充材料 / 退回修改后都回到"进入审核队列"重跑；</li>
 *   <li><b>四个终点</b>：校验失败、驳回、审核通过、超时关闭；</li>
 *   <li><b>模型在环</b>：两个审核结论节点是真的 agent 节点，靠系统提示词把输出约束成枚举值。</li>
 * </ul>
 *
 * <p>订单数据从输入 JSON 进来，由 {@code B} 解析成后续判定要用的变量；两个审核结论由模型给出。
 * 各场景的模型输出用 {@link ScriptedModel} 脚本化，因此整条路径是确定的、可断言的。</p>
 */
class OrderApprovalFlowTest {

    /** 金额阈值：超过就要转高级审核员复核。 */
    private static final int AMOUNT_THRESHOLD = 5_000;

    // ------------------------------------------------------------------
    // 图
    // ------------------------------------------------------------------

    static WorkflowDefinition orderApproval() {
        return JavaFlow.builder("order-approval")
                .description("订单审核：基础校验 → 风控 → 审核结论 → 金额分级 → 高级复核 → 履约/驳回/超时")

                // A 开始 → B 系统接收订单（解析订单 JSON，产出判定要用的变量）
                .start("A", "订单数据：${input}")
                .code("B", OrderApprovalFlowTest::receiveOrder)
                .chain("A", "B")

                // C 基础校验是否通过？否 → 记录原因并关闭
                .branchOn("C", "valid", c -> c.caseOf("true", "D").otherwise("C1"))
                .chain("B", "C")
                .code("C1", in -> NodeOutput.of("记录校验失败原因"))
                .end("C2", "【校验失败关闭】${C1}")
                .chain("C1", "C2")

                // D 进入审核队列 → E 是否命中风控规则？
                .code("D", in -> NodeOutput.of("进入审核队列", JavaFlow.args("status", "审核中")))
                .branchOn("E", "riskHit", c -> c.caseOf("true", "E1").otherwise("E2"))
                .chain("D", "E")
                .code("E1", in -> NodeOutput.of("转人工风控审核"))
                .code("E2", in -> NodeOutput.of("进入自动审核"))
                .chain("E1", "F")
                .chain("E2", "F")

                // F 风控审核结论（模型给出，三出口：通过 / 驳回 / 需补充材料）
                .agent("F", "只输出以下三个词之一：通过 / 驳回 / 需补充材料", "请给出风控审核结论：\n${last}")
                .routeFrom("F", "F", c -> c
                        .caseOf("通过", "G")
                        .caseOf("驳回", "H")
                        .caseOf("需补充材料", "I"))

                // G 金额是否超过阈值？否 → 自动通过；是 → 转高级审核员复核
                .branchOn("G", "overThreshold", c -> c.caseOf("true", "L").otherwise("K"))

                // H 驳回 → 结束
                .code("H", in -> NodeOutput.of("生成驳回通知"))
                .end("H1", "【驳回结束】${H}")
                .chain("H", "H1")

                // I 通知补充材料 → I1 是否在规定时间内补充？是 → 回到审核队列
                .code("I", in -> NodeOutput.of("通知商家补充材料"))
                .chain("I", "I1")
                .branchOn("I1", "supplemented", c -> c.caseOf("true", "D").otherwise("J"))

                // L 高级复核 → M 复核结论（模型给出，三出口：通过 / 驳回 / 退回修改）
                .code("L", in -> NodeOutput.of("转高级审核员复核"))
                .chain("L", "M")
                .agent("M", "只输出以下三个词之一：通过 / 驳回 / 退回修改", "请给出高级复核结论：\n${last}")
                .routeFrom("M", "M", c -> c
                        .caseOf("通过", "K")
                        .caseOf("驳回", "H")
                        .caseOf("退回修改", "N"))

                // N 退回商家修改 → N1 是否重新提交？是 → 回到审核队列
                .code("N", in -> NodeOutput.of("退回商家修改订单"))
                .chain("N", "N1")
                .branchOn("N1", "resubmitted", c -> c.caseOf("true", "D").otherwise("J"))

                // K 审核通过 → O 通知 → P 履约 → Q 结束
                .code("K", in -> NodeOutput.of("自动审核通过", JavaFlow.args("status", "已通过")))
                .chain("K", "O")
                .code("O", in -> NodeOutput.of("生成审核通过通知"))
                .chain("O", "P")
                .code("P", in -> NodeOutput.of("进入履约/发货流程"))
                .end("Q", "【审核通过】${O}｜${P}")
                .chain("P", "Q")

                // J 超时 → R 通知 → S 结束
                .code("J", in -> NodeOutput.of("触发超时计时器"))
                .code("R", in -> NodeOutput.of("发送超时关闭通知"))
                .end("S", "【超时关闭】${R}")
                .chain("J", "R")
                .chain("R", "S")
                .build();
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
                ScriptedModel.text("通过"),        // F → G（金额未超阈值）
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

    // ------------------------------------------------------------------
    // 结构
    // ------------------------------------------------------------------

    @Test
    @DisplayName("图结构与原图一致：26 个节点、四个终点，并行支线 T/U/V 被略去")
    void keepsTheWholeGraph() {
        WorkflowDefinition flow = orderApproval();

        // 节点集合与边数与原图一致（插入顺序按 DSL 书写，不是图的语义，故不约束）
        assertThat(flow.nodeIds()).containsExactlyInAnyOrder(
                "A", "B", "C", "C1", "C2", "D", "E", "E1", "E2", "F", "G", "H", "H1",
                "I", "I1", "J", "K", "L", "M", "N", "N1", "O", "P", "Q", "R", "S");
        // 原图里 D -. 并行通知 .-> T 这条支线不支持，也不该被悄悄造出来
        assertThat(flow.nodeIds()).doesNotContain("T", "U", "V");
        assertThat(flow.edges()).hasSize(31);
    }
}
