package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板插值：工作流里所有文本字段都过这一层，因此边界行为必须钉死。
 *
 * <p>最要紧的一条断言是"解析不到就保留原文" —— 它是刻意选的失败姿态，
 * 一旦被改成替换空串，提示词会静默缺块，排查成本陡增。</p>
 */
class TemplatesTest {

    private static final Map<String, Object> VARS = Map.of(
            "name", "本心",
            "count", 3,
            "flag", true,
            "step1", Map.of("data", List.of("甲", "乙"), "score", 88));

    @Test
    @DisplayName("单个与多个占位符都会被替换")
    void rendersPlaceholders() {
        assertThat(Templates.render("你好 ${name}", VARS)).isEqualTo("你好 本心");
        assertThat(Templates.render("${name} 有 ${count} 个", VARS)).isEqualTo("本心 有 3 个");
    }

    @Test
    @DisplayName("数字与布尔按字面量渲染")
    void rendersScalars() {
        assertThat(Templates.render("${count}", VARS)).isEqualTo("3");
        assertThat(Templates.render("${flag}", VARS)).isEqualTo("true");
    }

    @Test
    @DisplayName("解析不到的占位符保留原文，而不是变成空串")
    void keepsUnknownPlaceholder() {
        assertThat(Templates.render("前缀 ${nope} 后缀", VARS)).isEqualTo("前缀 ${nope} 后缀");
    }

    @Test
    @DisplayName("没有占位符的文本原样返回（含 shell 里的 ${VAR} 写法）")
    void leavesPlainTextAlone() {
        assertThat(Templates.render("echo ${PATH}", Map.of())).isEqualTo("echo ${PATH}");
        assertThat(Templates.render(null, VARS)).isNull();
        assertThat(Templates.render("", VARS)).isEmpty();
    }

    @Test
    @DisplayName("支持 ${key:-兜底} 语法")
    void supportsDefaultValue() {
        assertThat(Templates.render("${name:-匿名}", VARS)).isEqualTo("本心");
        assertThat(Templates.render("${missing:-匿名}", VARS)).isEqualTo("匿名");
        assertThat(Templates.render("${missing:-}", VARS)).isEmpty();
    }

    @Test
    @DisplayName("点路径可以下钻 Map 与 List")
    void resolvesNestedPath() {
        assertThat(Templates.render("${step1.score}", VARS)).isEqualTo("88");
        assertThat(Templates.render("${step1.data.1}", VARS)).isEqualTo("乙");
        assertThat(Templates.render("${step1.data.9}", VARS)).isEqualTo("${step1.data.9}");
        assertThat(Templates.render("${step1.nope}", VARS)).isEqualTo("${step1.nope}");
    }

    @Test
    @DisplayName("工具参数递归渲染，非字符串值原样保留")
    void rendersArguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("text", "值=${name}");
        args.put("limit", 10);
        args.put("nested", Map.of("inner", "${count}"));
        args.put("list", List.of("${name}", 7));

        Map<String, Object> rendered = Templates.renderArgs(args, VARS::get);

        assertThat(rendered).containsEntry("text", "值=本心");
        assertThat(rendered).containsEntry("limit", 10);
        assertThat(rendered.get("nested")).isEqualTo(Map.of("inner", "3"));
        assertThat(rendered.get("list")).isEqualTo(List.of("本心", 7));
    }

    @Test
    @DisplayName("placeholders 只列出硬依赖，带兜底值的不算")
    void listsPlaceholders() {
        assertThat(Templates.placeholders("${a} 与 ${b.c} 与 ${d:-x}"))
                .containsExactly("a", "b");
        assertThat(Templates.placeholders("没有占位符")).isEmpty();
        assertThat(Templates.placeholders(null)).isEmpty();
    }
}
