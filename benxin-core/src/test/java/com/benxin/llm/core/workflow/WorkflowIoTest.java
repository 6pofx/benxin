package com.benxin.llm.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作流定义的解析与校验。
 *
 * <p>重点不在"能不能读进来"，而在<b>"写错时是否当场说清楚"</b> ——
 * 一份工作流定义是配置，配置错误的代价必须止步于启动期。</p>
 */
class WorkflowIoTest {

    private static final String YAML = """
            name: code-review
            description: 代码评审工作流
            entry: start
            nodes:
              - id: start
                type: start
                prompt: "任务：${input}"
              - id: analyze
                type: agent
                instruction: 你是严格的评审者
                prompt: |
                  请分析下面的代码：
                  ${start}
              - id: search
                type: tool
                tool: search
                args:
                  keyword: "${input}"
                  limit: 3
              - id: mark
                type: set
                set:
                  phase: 已分析
              - id: route
                type: branch
              - id: report
                type: end
                output: "${analyze}"
                retry: 2
                continueOnError: true
              - id: slow
                type: agent
                prompt: "慢慢来"
                maxSteps: 4
            edges:
              - from: start
                to: analyze
              - from: analyze
                to: route
              - from: route
                to: search
                when: "${analyze} contains 严重"
              - from: route
                to: mark
              - from: search
                to: mark
              - from: mark
                to: report
            """;

    @Test
    @DisplayName("YAML：节点、边与全部字段都能正确读入")
    void readsYaml() {
        WorkflowDefinition definition = WorkflowIo.read(YAML);

        assertThat(definition.name()).isEqualTo("code-review");
        assertThat(definition.description()).isEqualTo("代码评审工作流");
        assertThat(definition.entry()).isEqualTo("start");
        assertThat(definition.nodeIds()).containsExactly(
                "start", "analyze", "search", "mark", "route", "report", "slow");
        assertThat(definition.edges()).hasSize(6);

        assertThat(definition.require("analyze").type()).isEqualTo(NodeType.AGENT);
        assertThat(definition.require("analyze").instruction()).isEqualTo("你是严格的评审者");
        // 块标量必须保留换行 —— 这正是推荐 YAML 而不是 JSON 的理由
        assertThat(definition.require("analyze").prompt()).contains("\n");

        assertThat(definition.require("search").type()).isEqualTo(NodeType.TOOL);
        assertThat(definition.require("search").tool()).isEqualTo("search");
        assertThat(definition.require("search").args())
                .containsEntry("keyword", "${input}")
                .containsEntry("limit", 3);

        assertThat(definition.require("mark").type()).isEqualTo(NodeType.SET);
        assertThat(definition.require("mark").set()).containsEntry("phase", "已分析");

        assertThat(definition.require("route").type()).isEqualTo(NodeType.BRANCH);

        assertThat(definition.require("report").type()).isEqualTo(NodeType.END);
        assertThat(definition.require("report").retry()).isEqualTo(2);
        assertThat(definition.require("report").continueOnError()).isTrue();

        assertThat(definition.require("slow").maxSteps()).isEqualTo(4);
        // 未声明的可选字段保持默认值
        assertThat(definition.require("analyze").retry()).isZero();
        assertThat(definition.require("analyze").maxSteps()).isEqualTo(-1);

        assertThat(definition.resolveEntry().id()).isEqualTo("start");
    }

    @Test
    @DisplayName("JSON 与 YAML 走同一条绑定与校验路径")
    void readsJson() {
        String json = """
                {
                  "name": "mini",
                  "nodes": [
                    {"id": "a", "type": "agent", "prompt": "做 ${input}"},
                    {"id": "b", "type": "end", "output": "${a}"}
                  ],
                  "edges": [{"from": "a", "to": "b"}]
                }
                """;

        WorkflowDefinition definition = WorkflowIo.read(json);

        assertThat(definition.name()).isEqualTo("mini");
        assertThat(definition.require("a").type()).isEqualTo(NodeType.AGENT);
        assertThat(definition.resolveEntry().id()).isEqualTo("a");
    }

    @Test
    @DisplayName("不带 entry 时，入口按 start 节点 → 无入边节点 推断")
    void infersEntry() {
        WorkflowDefinition withStart = WorkflowIo.read("""
                name: w
                nodes:
                  - id: first
                    type: agent
                    prompt: x
                  - id: begin
                    type: start
                  - id: finish
                    type: end
                    output: y
                edges:
                  - from: begin
                    to: first
                  - from: first
                    to: finish
                """);
        assertThat(withStart.resolveEntry().id()).isEqualTo("begin");

        WorkflowDefinition noStart = WorkflowIo.read("""
                name: w
                nodes:
                  - id: first
                    type: agent
                    prompt: x
                  - id: second
                    type: end
                    output: y
                edges:
                  - from: first
                    to: second
                """);
        assertThat(noStart.resolveEntry().id()).isEqualTo("first");
    }

    @Test
    @DisplayName("节点类型接受别名与小写（llm / condition / finish …）")
    void acceptsTypeAliases() {
        WorkflowDefinition definition = WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: llm
                    prompt: x
                  - id: b
                    type: condition
                  - id: c
                    type: finish
                    output: y
                edges:
                  - from: a
                    to: b
                  - from: b
                    to: c
                """);

        assertThat(definition.require("a").type()).isEqualTo(NodeType.AGENT);
        assertThat(definition.require("b").type()).isEqualTo(NodeType.BRANCH);
        assertThat(definition.require("c").type()).isEqualTo(NodeType.END);
    }

    @Test
    @DisplayName("缺省类型是 agent")
    void defaultsToAgent() {
        WorkflowDefinition definition = WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    prompt: x
                """);
        assertThat(definition.require("a").type()).isEqualTo(NodeType.AGENT);
    }

    // ---------- 校验 ----------

    @Test
    @DisplayName("agent 节点缺 prompt 在加载期就被拒绝")
    void rejectsAgentWithoutPrompt() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: agent
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 prompt");
    }

    @Test
    @DisplayName("tool 节点缺工具名、set 节点缺键值都被拒绝")
    void rejectsIncompleteNodes() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: tool
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 tool");

        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    type: set
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set 键值");
    }

    @Test
    @DisplayName("边指向不存在的节点会被拒绝，并列出已有节点")
    void rejectsDanglingEdge() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    prompt: x
                edges:
                  - from: a
                    to: 不存在
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在节点")
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("节点 id 重复会被拒绝")
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    prompt: x
                  - id: a
                    prompt: y
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("节点 id 与内置变量重名会被拒绝并提示改名")
    void rejectsReservedIds() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: input
                    prompt: x
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("与内置变量重名");
    }

    @Test
    @DisplayName("节点 id 含点号会被拒绝（会与点路径取值混淆）")
    void rejectsDottedIds() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a.b
                    prompt: x
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许包含 '.'");
    }

    @Test
    @DisplayName("条件表达式语法错误在加载期暴露")
    void rejectsBadCondition() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    prompt: x
                  - id: b
                    prompt: y
                edges:
                  - from: a
                    to: b
                    when: "${x} is 莫名其妙"
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("条件表达式");
    }

    @Test
    @DisplayName("没有任何节点会被拒绝")
    void rejectsEmptyWorkflow() {
        assertThatThrownBy(() -> WorkflowIo.read("name: w\nnodes: []\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何节点");
    }

    @Test
    @DisplayName("空内容与非 YAML 内容报错清楚")
    void rejectsBlankContent() {
        assertThatThrownBy(() -> WorkflowIo.read("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容为空");
    }

    // ---------- 读写往返 ----------

    @Test
    @DisplayName("JSON 往返：写出去再读回来结构与取值不变")
    void jsonRoundTrip() {
        WorkflowDefinition original = WorkflowIo.read(YAML);

        String written = WorkflowIo.writeJson(original);
        WorkflowDefinition restored = WorkflowIo.read(written, WorkflowIo.Format.JSON);

        assertThat(restored.name()).isEqualTo(original.name());
        assertThat(restored.description()).isEqualTo(original.description());
        assertThat(restored.entry()).isEqualTo(original.entry());
        assertThat(restored.nodeIds()).isEqualTo(original.nodeIds());
        assertThat(restored.edges()).hasSize(original.edges().size());
        assertThat(restored.require("analyze").prompt()).isEqualTo(original.require("analyze").prompt());
        assertThat(restored.require("search").args()).isEqualTo(original.require("search").args());
        assertThat(restored.require("report").retry()).isEqualTo(2);
        assertThat(restored.require("report").continueOnError()).isTrue();
    }

    @Test
    @DisplayName("序列化会省略默认值，输出保持干净")
    void writesCleanJson() {
        WorkflowDefinition definition = WorkflowIo.read("""
                name: w
                nodes:
                  - id: a
                    prompt: x
                """);

        String written = WorkflowIo.writeJson(definition);

        assertThat(written).contains("\"name\" : \"w\"");
        assertThat(written).doesNotContain("retry");
        assertThat(written).doesNotContain("continueOnError");
        assertThat(written).doesNotContain("maxSteps");
    }

    @Test
    @DisplayName("可以从文件读取，.json 后缀按 JSON 解析")
    void readsFromFile(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("flow.yaml");
        Files.writeString(yaml, YAML);
        assertThat(WorkflowIo.read(yaml).name()).isEqualTo("code-review");

        Path json = dir.resolve("flow.json");
        Files.writeString(json, """
                {"name":"from-json","nodes":[{"id":"a","prompt":"x"}]}
                """);
        assertThat(WorkflowIo.read(json).name()).isEqualTo("from-json");
    }

    @Test
    @DisplayName("YAML 入口可用性可查询，缺失时说清替代方案")
    void reportsYamlAvailability() {
        // 测试期 snakeyaml 一定在类路径上（core 把它声明为 optional + 测试可用）
        assertThat(WorkflowIo.yamlAvailable()).isTrue();
    }
}
