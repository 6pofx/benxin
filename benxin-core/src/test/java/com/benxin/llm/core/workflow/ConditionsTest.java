package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条件表达式：工作流的分支全靠它，因此这里既测"能算对"，也测"写错时怎么报"。
 */
class ConditionsTest {

    private static final Map<String, Object> VARS = Map.of(
            "status", "done",
            "verdict", "不通过",
            "analyze", "发现了严重的内存泄漏",
            "count", 12,
            "empty", "",
            "list", java.util.List.of("a", "b"));

    private static boolean check(String expression) {
        return Conditions.evaluate(expression, VARS::get);
    }

    // ---------- 相等与不等 ----------

    @Test
    @DisplayName("== 与 != 按值比较，数字按数值比较")
    void equality() {
        assertThat(check("${status} == done")).isTrue();
        assertThat(check("${status} == \"done\"")).isTrue();
        assertThat(check("${status} != done")).isFalse();
        assertThat(check("${count} == 12")).isTrue();
        // 字符串 "12" 与数字 12 也应当相等 —— 黑板上拿到的值类型不稳定，不该由作者操心
        assertThat(check("${count} == \"12\"")).isTrue();
    }

    @Test
    @DisplayName("裸词先当变量查、查不到才当字面量")
    void bareWordResolution() {
        assertThat(check("status == done")).isTrue();
        assertThat(check("${status} == 一个不存在的词")).isFalse();
        assertThat(check("一个不存在的词 == 一个不存在的词")).isTrue();
    }

    // ---------- 包含 / 正则 ----------

    @Test
    @DisplayName("contains 对字符串、集合、Map 都有意义")
    void contains() {
        assertThat(check("${analyze} contains 严重")).isTrue();
        assertThat(check("${analyze} contains 不存在")).isFalse();
        assertThat(check("${list} contains a")).isTrue();
        assertThat(check("${analyze} not contains 严重")).isFalse();
    }

    @Test
    @DisplayName("matches 是正则 find 语义")
    void matches() {
        assertThat(check("${analyze} matches .*内存.*")).isTrue();
        assertThat(check("${analyze} matches ^严重")).isFalse();
        assertThat(check("${analyze} matches 泄漏$")).isTrue();
    }

    @Test
    @DisplayName("matches 的右操作数按原文读取：括号与竖线不会被拆坏")
    void regexOperandIsRaw() {
        // (?i) 是最常见的正则前缀，先分词再拼回去一定会把它拆成语法错误
        assertThat(check("${analyze} matches (?i)MEMORY|内存")).isTrue();
        assertThat(check("${analyze} matches (?i)leak")).isFalse();
        // 正则与逻辑运算混用：正则在 and 之前结束
        assertThat(check("${analyze} matches 严重 and ${count} > 10")).isTrue();
        assertThat(check("${analyze} matches 严重 and ${count} > 100")).isFalse();
        // 带引号时按字面量处理
        assertThat(check("${analyze} matches \"严重\"")).isTrue();
    }

    @Test
    @DisplayName("正则写错判 false 而不是抛异常")
    void invalidRegexIsFalse() {
        assertThat(check("${analyze} matches [未闭合")).isFalse();
    }

    // ---------- 数值比较 ----------

    @ParameterizedTest
    @CsvSource({
            "'${count} > 10', true",
            "'${count} >= 12', true",
            "'${count} < 10', false",
            "'${count} <= 12', true",
            "'${count} > 100', false"
    })
    @DisplayName("数值比较")
    void numericComparison(String expression, boolean expected) {
        assertThat(check(expression)).isEqualTo(expected);
    }

    @Test
    @DisplayName("非数字参与大小比较判 false，而不是抛异常")
    void nonNumericComparisonIsFalse() {
        assertThat(check("${status} > 10")).isFalse();
    }

    // ---------- 空 / 存在 ----------

    @Test
    @DisplayName("is empty / is not empty / 为空")
    void emptiness() {
        assertThat(check("${empty} is empty")).isTrue();
        assertThat(check("${status} is not empty")).isTrue();
        assertThat(check("${status} is empty")).isFalse();
        assertThat(check("${missing} is empty")).isTrue();
        assertThat(check("${empty} 为空")).isTrue();
    }

    @Test
    @DisplayName("exists / missing")
    void existence() {
        assertThat(check("${status} exists")).isTrue();
        assertThat(check("${missing} exists")).isFalse();
        assertThat(check("${missing} missing")).isTrue();
    }

    // ---------- 逻辑组合 ----------

    @Test
    @DisplayName("and / or / not 与括号，符号与中文写法等价")
    void logicalOperators() {
        assertThat(check("${status} == done and ${count} > 10")).isTrue();
        assertThat(check("${status} == other and ${count} > 10")).isFalse();
        assertThat(check("${status} == other or ${count} > 10")).isTrue();
        assertThat(check("not (${status} == done)")).isFalse();
        assertThat(check("${status} == done && ${count} > 10")).isTrue();
        assertThat(check("${status} == other || ${count} > 10")).isTrue();
        assertThat(check("${status} == done 且 ${count} > 10")).isTrue();
        assertThat(check("${status} == other 或 ${count} > 10")).isTrue();
        assertThat(check("非 (${status} == done)")).isFalse();
        assertThat(check("(${status} == done or ${count} > 100) and ${count} >= 12")).isTrue();
    }

    @Test
    @DisplayName("not 前缀作用在比较与真值上")
    void notPrefix() {
        assertThat(check("not ${status} == done")).isFalse();
        assertThat(check("not ${missing}")).isTrue();
        assertThat(check("${status} not contains 严重")).isTrue();
        assertThat(check("not (${status} not contains 严重)")).isFalse();
    }

    @Test
    @DisplayName("裸值走真值判断：空串 / false / 0 为假")
    void truthiness() {
        assertThat(check("${status}")).isTrue();
        assertThat(check("${empty}")).isFalse();
        assertThat(check("false")).isFalse();
        assertThat(check("0")).isFalse();
        assertThat(check("1")).isTrue();
    }

    // ---------- 默认分支与校验 ----------

    @Test
    @DisplayName("空条件恒真 —— 这就是默认分支的写法")
    void blankIsDefaultBranch() {
        assertThat(Conditions.evaluate(null, VARS::get)).isTrue();
        assertThat(Conditions.evaluate("", VARS::get)).isTrue();
        assertThat(Conditions.evaluate("   ", VARS::get)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "${a} == ",
            "${a} is",
            "${a} is 未知状态",
            "${a} == 1 尾巴",
            "and ${a}",
            "${a} not 什么",
            "(${a} == 1"
    })
    @DisplayName("语法错误在加载期就报出来，且报错信息带上原表达式")
    void syntaxErrorsAreReported(String expression) {
        assertThatThrownBy(() -> Conditions.validate(expression))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("条件表达式");
    }

    @Test
    @DisplayName("未闭合的 ${ 也能被拒绝而不是静默通过")
    void unclosedPlaceholder() {
        assertThatThrownBy(() -> Conditions.validate("${a == 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("合法表达式通过校验器")
    void validateAcceptsValidExpressions() {
        Conditions.validate("${verdict} == 通过 and not (${count} > 100)");
        Conditions.validate(null);
    }

    @Test
    @DisplayName("lookup 为 null 时不抛异常（未提供黑板时按未取到处理）")
    void nullLookupIsSafe() {
        Function<String, Object> none = null;
        assertThat(Conditions.evaluate("${a} == 1", none)).isFalse();
        assertThat(Conditions.evaluate("${a} is empty", none)).isTrue();
    }
}
