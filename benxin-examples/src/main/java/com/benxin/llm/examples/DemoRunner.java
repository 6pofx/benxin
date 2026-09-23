package com.benxin.llm.examples;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.tool.ToolScanner;
import com.benxin.llm.examples.agent.CodeReviewer;
import com.benxin.llm.examples.agent.PatchEngineer;
import com.benxin.llm.examples.agent.Translator;
import com.benxin.llm.examples.agent.WeatherAssistant;
import com.benxin.llm.examples.guard.AuditInterceptor;
import com.benxin.llm.examples.tool.WeatherTools;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动即跑的演示：把本心的每一条主张都用可观察的输出验证一遍。
 *
 * <p>所有演示都包了 try/catch —— 示例应用的职责是"展示"，不是"因为某个可选能力没开就崩掉"。</p>
 */
@Component
@ConditionalOnProperty(prefix = "benxin.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final WeatherAssistant weatherAssistant;
    private final CodeReviewer codeReviewer;
    private final Translator translator;
    private final PatchEngineer patchEngineer;
    private final AuditInterceptor auditInterceptor;
    private final AgentRegistry agentRegistry;
    private final LlmAgentFactory agentFactory;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final WeatherTools weatherTools;
    private final LlmModel mockModel;

    public DemoRunner(WeatherAssistant weatherAssistant,
                      CodeReviewer codeReviewer,
                      Translator translator,
                      PatchEngineer patchEngineer,
                      AuditInterceptor auditInterceptor,
                      AgentRegistry agentRegistry,
                      LlmAgentFactory agentFactory,
                      ModelRegistry modelRegistry,
                      LoopRegistry loopRegistry,
                      WeatherTools weatherTools,
                      LlmModel mockModel) {
        this.weatherAssistant = weatherAssistant;
        this.codeReviewer = codeReviewer;
        this.translator = translator;
        this.patchEngineer = patchEngineer;
        this.auditInterceptor = auditInterceptor;
        this.agentRegistry = agentRegistry;
        this.agentFactory = agentFactory;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.weatherTools = weatherTools;
        this.mockModel = mockModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        banner();
        demo1DeclarativeCall();
        demo2ToolCalling();
        demo3CustomLoop();
        demo4Streaming();
        demo5TypedReturn();
        demo6ProgrammaticAssembly();
        footer();
    }

    private void banner() {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  本心 benxin · 声明式 LLM Agent 调用框架 —— 离线演示");
        System.out.println("  模型: " + modelRegistry.names() + "（默认 " + modelRegistry.defaultName() + "）");
        System.out.println("  Loop: " + loopRegistry.names());
        System.out.println("  Agent: " + agentRegistry.names());
        System.out.println("=".repeat(78));
    }

    /** ① 最核心的一条：注解声明接口，注入即用，调用方看不到任何 LLM API。 */
    private void demo1DeclarativeCall() {
        section("① 声明式调用 —— @LlmAgent 接口直接注入");
        try {
            String answer = weatherAssistant.askWithContext("我在准备明天出差", "北京天气怎么样？");
            System.out.println("回答: " + answer);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** ② 工具调用闭环：模型请求工具 → 框架执行 → 结果回灌 → 模型收尾。 */
    private void demo2ToolCalling() {
        section("② 工具调用 —— 普通 Java 方法打上 @LlmTool 即交给模型");
        System.out.println("已注册工具: " + ToolScanner.scan(weatherTools).stream()
                .map(t -> t.name()).toList());
        try {
            String answer = weatherAssistant.ask("帮我查一下上海的天气");
            System.out.println("回答: " + answer);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** ③ 自定义 Loop：示例应用自己写的 step-by-step。 */
    private void demo3CustomLoop() {
        section("③ 自定义 Loop —— 一个 @LlmLoop 注解就被注册进框架");
        try {
            String answer = translator.translate("The quick brown fox jumps over the lazy dog.",
                    "中文", "demo-session-1");
            System.out.println("回答: " + answer);
            System.out.println("会话历史条数: " + agentRegistry.get("translator").history("demo-session-1").size());
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** ④ 流式：接口多一个 LlmStreamHandler 参数即可，不需要另一种 API。 */
    private void demo4Streaming() {
        section("④ 流式输出 —— 增量实时到达");
        try {
            StringBuilder collected = new StringBuilder();
            LlmStreamHandler handler = new LlmStreamHandler() {
                @Override
                public void onTextDelta(String delta) {
                    collected.append(delta);
                    System.out.print(delta);
                }
            };
            translator.translateStreaming("Hello, benxin!", "中文", handler);
            System.out.println();
            System.out.println("共收到 " + collected.length() + " 个字符的增量");
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** ⑤ 强类型返回：模型输出 JSON 自动反序列化成 DTO。 */
    private void demo5TypedReturn() {
        section("⑤ 强类型返回 —— 模型输出直接变成 Java 对象");
        try {
            CodeReviewer.ReviewReport report = codeReviewer.review("public class A { void f() { } }");
            System.out.println("总评: " + report.summary());
            System.out.println("问题: " + report.issues());
            System.out.println("评分: " + report.score());
            System.out.println("拦截器统计: 运行 " + auditInterceptor.runs()
                    + " 次 / 工具 " + auditInterceptor.toolCalls() + " 次（仅 codeReviewer 生效）");
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** ⑥ 程序化组装：不用注解、不写 yml，纯 Java 换掉任何一个组件。 */
    private void demo6ProgrammaticAssembly() {
        section("⑥ 程序化组装 —— 不用注解也能换掉任意组件");
        try {
            Agent agent = Agent.builder("manual-agent")
                    .model(mockModel)
                    .loop(loopRegistry.get("react"))
                    .tools(ToolScanner.scan(weatherTools))
                    .systemPrompt("你是一个手工装配的助手。")
                    .maxSteps(6)
                    .build();
            AgentResult result = agent.call("现在几点了？");
            System.out.println("装配结果: " + agent);
            System.out.println("回答: " + result.text());
            System.out.println("步数 " + result.steps() + " / 用时 " + result.durationMillis() + " ms");
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    private void footer() {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  演示结束。HTTP 接口（llm.web.enabled=true 时开启）：");
        System.out.println("    GET  /llm/agents                       列出全部 Agent");
        System.out.println("    GET  /llm/loops                        列出全部 Loop");
        System.out.println("    POST /llm/agents/weatherAssistant/chat 同步对话");
        System.out.println("    GET  /llm/agents/weatherAssistant/stream?message=... SSE 流式");
        System.out.println("  接真实模型：mvn spring-boot:run -Dspring-boot.run.profiles=real");
        System.out.println("=".repeat(78));
        System.out.println();
    }

    private void section(String title) {
        System.out.println();
        System.out.println("── " + title + " " + "─".repeat(Math.max(0, 60 - title.length())));
    }

    private void failed(RuntimeException e) {
        System.out.println("演示跳过（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
        log.debug("演示失败详情", e);
    }

    /** 便于其它示例引用：把一组工具对象转成工具列表。 */
    static List<String> toolNamesOf(Object... beans) {
        return ToolScanner.scanAll(beans).stream().map(t -> t.name()).toList();
    }
}