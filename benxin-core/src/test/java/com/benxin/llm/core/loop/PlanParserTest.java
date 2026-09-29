package com.benxin.llm.core.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编号计划解析。这段容错逻辑原本长在 {@code PlanExecuteLoop} 里，
 * 现在被 {@code plan-execute} 与 {@code staged} 共用，因此单独钉住它的边界行为。
 */
class PlanParserTest {

    @Test
    @DisplayName("常见的列表前缀都能识别")
    void recognizesListPrefixes() {
        assertThat(PlanParser.parse("""
                1. 读取配置
                2) 校验参数
                3、执行任务
                第 4 步：输出结果
                步骤5: 收尾
                Step 6: 复核"""))
                .containsExactly("读取配置", "校验参数", "执行任务", "输出结果", "收尾", "复核");
    }

    @Test
    @DisplayName("项目符号也算步骤")
    void recognizesBullets() {
        assertThat(PlanParser.parse("- 甲\n* 乙\n• 丙\n· 丁"))
                .containsExactly("甲", "乙", "丙", "丁");
    }

    @Test
    @DisplayName("加粗条目先剥壳再匹配")
    void stripsBold() {
        assertThat(PlanParser.parse("**1. 读取配置**\n**2. 输出报告**：附上截图"))
                .containsExactly("读取配置", "输出报告：附上截图");
    }

    @Test
    @DisplayName("标题行与前言不算步骤")
    void ignoresHeadersAndProse() {
        assertThat(PlanParser.parse("""
                计划：
                下面是我的执行计划，请确认。
                1. 第一步
                以上就是全部。"""))
                .containsExactly("第一步");
    }

    @Test
    @DisplayName("一条都抽不出来时返回空列表，由调用方决定降级方式")
    void returnsEmptyWhenNothingParses() {
        assertThat(PlanParser.parse("我觉得直接做就行")).isEmpty();
        assertThat(PlanParser.parse("")).isEmpty();
        assertThat(PlanParser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("超过上限时截断，并可被调用方检测到")
    void truncatesAtLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= PlanParser.MAX_STEPS + 20; i++) {
            sb.append(i).append(". 步骤").append(i).append('\n');
        }

        List<String> steps = PlanParser.parse(sb.toString());

        assertThat(steps).hasSize(PlanParser.MAX_STEPS);
        assertThat(PlanParser.truncated(steps)).isTrue();
        assertThat(PlanParser.truncated(List.of("一个"))).isFalse();
    }
}
