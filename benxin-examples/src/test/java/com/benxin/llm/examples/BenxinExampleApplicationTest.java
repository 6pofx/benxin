package com.benxin.llm.examples;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.tool.ToolScanner;
import com.benxin.llm.examples.agent.CodeReviewer;
import com.benxin.llm.examples.agent.PatchEngineer;
import com.benxin.llm.examples.agent.ReleaseNoteWriter;
import com.benxin.llm.examples.agent.StagedAnalyst;
import com.benxin.llm.examples.agent.Translator;
import com.benxin.llm.examples.agent.WeatherAssistant;
import com.benxin.llm.examples.tool.WeatherTools;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmToolCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 示例应用的装配验证。
 *
 * <p>关掉 DemoRunner（它只负责往控制台打印），专注验证"示例里展示的每一条能力都真的接上了"：
 * 四个声明式 Agent 可注入、自定义 Loop 被注册、三协议之外的 mock 模型被登记、
 * 工具目录扫到了示例工具。</p>
 */
@SpringBootTest(properties = {
        "benxin.demo.enabled=false",
        "llm.web.enabled=false"
})
class BenxinExampleApplicationTest {

    @Autowired
    private WeatherAssistant weatherAssistant;

    @Autowired
    private CodeReviewer codeReviewer;

    @Autowired
    private PatchEngineer patchEngineer;

    @Autowired
    private Translator translator;

    @Autowired
    private ReleaseNoteWriter releaseNoteWriter;

    @Autowired
    private StagedAnalyst stagedAnalyst;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private LoopRegistry loopRegistry;

    @Autowired
    private LlmToolCatalog toolCatalog;

    @Autowired
    private WeatherTools weatherTools;

    @Test
    @DisplayName("四个声明式 Agent 接口都能被注入")
    void declarativeAgentsAreInjectable() {
        assertThat(weatherAssistant).isNotNull();
        assertThat(codeReviewer).isNotNull();
        assertThat(patchEngineer).isNotNull();
        assertThat(translator).isNotNull();
    }

    @Test
    @DisplayName("示例里的四个 Agent 都被登记进注册表")
    void allExampleAgentsRegistered() {
        assertThat(agentRegistry.names())
                .contains("weatherAssistant", "codeReviewer", "patchEngineer", "translator");
    }

    @Test
    @DisplayName("每个 Agent 用的是注解上声明的 Loop")
    void agentsUseDeclaredLoops() {
        assertThat(agentRegistry.get("weatherAssistant").loop().name()).isEqualTo("dsh-minimal");
        assertThat(agentRegistry.get("codeReviewer").loop().name()).isEqualTo("claude-code");
        assertThat(agentRegistry.get("patchEngineer").loop().name()).isEqualTo("codex");
        // 示例应用自己写的 Loop，靠 @LlmLoop 注解被自动注册
        assertThat(agentRegistry.get("translator").loop().name()).isEqualTo("step-by-step");
    }

    @Test
    @DisplayName("自定义 Loop 出现在注册表里")
    void customLoopIsRegistered() {
        assertThat(loopRegistry.names())
                .contains("dsh-minimal", "react", "claude-code", "codex", "plan-execute", "reflexion",
                        "step-by-step");
    }

    // ------------------------------------------------------------------
    // 工作流模式（两条路线）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两个工作流模式的示例 Agent 都可注入")
    void workflowAgentsAreInjectable() {
        assertThat(releaseNoteWriter).isNotNull();
        assertThat(stagedAnalyst).isNotNull();
    }

    @Test
    @DisplayName("llm.workflows.release-notes 把 YAML 注册成了 workflow:release-notes 这个 Loop")
    void declaredWorkflowBecomesLoop() {
        assertThat(loopRegistry.names()).contains("workflow:release-notes", "staged", "workflow");
        assertThat(loopRegistry.get("workflow:release-notes"))
                .isInstanceOf(com.benxin.llm.core.loop.WorkflowLoop.class);
        // 声明式工作流的 Loop 名必须等于注册名，否则 /llm/agents 会显示一个查不到的名字
        assertThat(loopRegistry.get("workflow:release-notes").name()).isEqualTo("workflow:release-notes");
    }

    @Test
    @DisplayName("示例 Agent 分别指向声明式工作流与结构化编排")
    void workflowAgentsUseDeclaredLoops() {
        assertThat(agentRegistry.get("releaseNoteWriter").loop().name()).isEqualTo("workflow:release-notes");
        assertThat(agentRegistry.get("stagedAnalyst").loop().name()).isEqualTo("staged");
    }

    @Test
    @DisplayName("声明式工作流真的按图跑：同一张图因输入不同走不同分支")
    void declarativeWorkflowTakesDifferentBranches() {
        // 不含"紧急" → 落默认边，正常通道
        String normal = releaseNoteWriter.draft("新增了导出 CSV 的功能");
        assertThat(normal).contains("常规通道").contains("（本次为常规发布，无需加急补充）");

        // 含"紧急" → 命中条件边，走加急通道，并且 tool 节点取到了时间戳
        String urgent = releaseNoteWriter.draft("紧急修复：支付回调偶发超时");
        assertThat(urgent).contains("加急通道").contains("【加急补充】");
        assertThat(urgent).doesNotContain("（本次为常规发布，无需加急补充）");
    }

    @Test
    @DisplayName("结构化编排走完规划 → 执行 → 校验 → 汇总，阶段与结论都留档在 attributes 里")
    void stagedWorkflowRunsAllStages() {
        var result = agentRegistry.get("stagedAnalyst")
                .call("分析一下把「协议」与「厂商」分开的收益");

        assertThat(result.attributes().get("benxin.staged.stage")).isEqualTo("synthesize");
        assertThat(result.attributes().get("benxin.staged.verdict")).isEqualTo("pass");
        assertThat((java.util.List<?>) result.attributes().get("benxin.staged.plan")).hasSize(3);
        assertThat(result.text()).isNotBlank();
    }

    @Test
    @DisplayName("离线 Mock 模型被登记为默认模型")
    void mockModelIsDefault() {
        assertThat(modelRegistry.names()).contains("mock");
        assertThat(modelRegistry.defaultName()).isEqualTo("mock");
    }

    @Test
    @DisplayName("示例工具被打上 @LlmTool 后进入工具目录")
    void exampleToolsAreScanned() {
        assertThat(ToolScanner.scan(weatherTools).stream().map(t -> t.name()).toList())
                .containsExactlyInAnyOrder("get_weather", "get_current_time", "calculate");
        assertThat(toolCatalog.global().names())
                .contains("get_weather", "get_current_time", "calculate");
    }

    @Test
    @DisplayName("只声明了部分工具的 Agent 不会拿到全部工具")
    void agentToolsAreScopedToDeclaredClasses() {
        Agent weather = agentRegistry.get("weatherAssistant");

        assertThat(weather.tools().names()).contains("get_weather");
        assertThat(weather.tools().names()).hasSize(3);
    }

    @Test
    @DisplayName("注解上的 maxSteps 生效")
    void maxStepsFromAnnotation() {
        assertThat(agentRegistry.get("weatherAssistant").spec().maxSteps()).isEqualTo(6);
        assertThat(agentRegistry.get("patchEngineer").spec().maxSteps()).isEqualTo(20);
    }

    @Test
    @DisplayName("内置文件/命令工具默认不装配 —— 示例是安全默认值的活证据")
    void builtinToolsAreOffByDefault() {
        assertThat(toolCatalog.global().names()).doesNotContain("read", "write", "edit", "bash", "glob", "grep");
    }
}
