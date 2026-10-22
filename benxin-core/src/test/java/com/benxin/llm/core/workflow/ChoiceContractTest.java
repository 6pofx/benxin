package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出契约的判定规则。
 *
 * <p>这里钉的都是"模型不听话"的具体形态：Markdown、加粗、引号、尾标点、"结论："前缀、
 * 客气话，以及最危险的一种 —— <b>否定说法</b>（"不通过"里含"通过"）。</p>
 */
class ChoiceContractTest {

    private static final List<String> VERDICTS = List.of("通过", "驳回", "需补充材料");

    @Test
    @DisplayName("归一化：剥掉 Markdown 围栏、加粗、列表前缀、引号与尾标点")
    void normalizesCommonNoise() {
        assertThat(ChoiceContract.normalize("通过")).isEqualTo("通过");
        assertThat(ChoiceContract.normalize("  **通过**。 ")).isEqualTo("通过");
        assertThat(ChoiceContract.normalize("```\n通过\n```")).isEqualTo("通过");
        assertThat(ChoiceContract.normalize("```json\n驳回\n```")).isEqualTo("驳回");
        assertThat(ChoiceContract.normalize("- 通过")).isEqualTo("通过");
        assertThat(ChoiceContract.normalize("「通过」")).isEqualTo("通过");
        assertThat(ChoiceContract.normalize("结论：驳回")).isEqualTo("驳回");
        assertThat(ChoiceContract.normalize("审核结论: 需补充材料。")).isEqualTo("需补充材料");
        assertThat(ChoiceContract.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("判定：客气话、加粗、句号都不影响结果")
    void matchesDespiteModelChatter() {
        assertThat(ChoiceContract.match("好的，我的判断是：通过。", VERDICTS)).isEqualTo("通过");
        assertThat(ChoiceContract.match("**驳回**", VERDICTS)).isEqualTo("驳回");
        assertThat(ChoiceContract.match("结论：需补充材料", VERDICTS)).isEqualTo("需补充材料");
        assertThat(ChoiceContract.match("```\n通过\n```", VERDICTS)).isEqualTo("通过");
    }

    @Test
    @DisplayName("否定说法不算命中：『不通过』绝不能被当成『通过』")
    void negationIsNotAMatch() {
        assertThat(ChoiceContract.match("不通过", VERDICTS)).isNull();
        assertThat(ChoiceContract.match("审核不通过", VERDICTS)).isNull();
        assertThat(ChoiceContract.match("未通过", VERDICTS)).isNull();
    }

    @Test
    @DisplayName("选项之间互为子串时按最具体的一个算，不会两边都命中")
    void prefersTheMoreSpecificChoice() {
        List<String> choices = List.of("通过", "不通过");

        assertThat(ChoiceContract.match("不通过", choices)).isEqualTo("不通过");
        assertThat(ChoiceContract.match("审核结果：不通过。", choices)).isEqualTo("不通过");
        assertThat(ChoiceContract.match("通过", choices)).isEqualTo("通过");
    }

    @Test
    @DisplayName("判不出来就是判不出来：空串、含糊表述、多个命中都返回 null")
    void refusesToGuess() {
        assertThat(ChoiceContract.match("", VERDICTS)).isNull();
        assertThat(ChoiceContract.match("   ", VERDICTS)).isNull();
        assertThat(ChoiceContract.match("我觉得都行，你看着办", VERDICTS)).isNull();
        // 一句话里同时出现两个选项 → 歧义，宁可重问
        assertThat(ChoiceContract.match("不是驳回就是通过", VERDICTS)).isNull();
    }

    @Test
    @DisplayName("没有契约时原样返回，不做任何加工")
    void withoutChoicesReturnsRaw() {
        assertThat(ChoiceContract.match("随便说点什么", List.of())).isEqualTo("随便说点什么");
        assertThat(ChoiceContract.match(null, null)).isNull();
    }

    @Test
    @DisplayName("纠正消息里必须列全允许取值，否则模型没法学")
    void correctionListsEveryChoice() {
        String message = ChoiceContract.correction(VERDICTS);

        assertThat(message).contains("通过").contains("驳回").contains("需补充材料");
        assertThat(message).contains("只输出");
    }
}
