package com.benxin.llm.core;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.impl.AnthropicModel;
import com.benxin.llm.core.model.impl.GeminiModel;
import com.benxin.llm.core.model.impl.OpenAiModel;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolScanner;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;

/**
 * 本心门面：不用 Spring 时的统一入口。
 *
 * <p>{@code benxin-core} 刻意不依赖 Spring，因此它可以被用在批处理、CLI、测试、甚至
 * 另一个框架里。下面这几行就是一个完整的"裸 Java 用法"：</p>
 *
 * <pre>{@code
 * LlmModel model = Benxin.openAi(ModelConfig.builder("deepseek")
 *         .baseUrl("https://api.deepseek.com").apiKey(key).model("deepseek-chat").build());
 *
 * Agent agent = Benxin.agent("assistant")
 *         .model(model)
 *         .loop(Benxin.loops().get("dsh-minimal"))
 *         .tools(Benxin.tools(new MyTools()))
 *         .build();
 *
 * System.out.println(agent.call("你好").text());
 * }</pre>
 */
public final class Benxin {

    /** 与 Maven 版本号保持一致。 */
    public static final String VERSION = "1.0.0";

    private Benxin() {
    }

    // ---------- 装配入口 ----------

    public static AgentBuilder agent(String name) {
        return Agent.builder(name).loopRegistry(loops()).modelRegistry(models());
    }

    /** 全新的空 Loop 注册表；需要内置 Loop 用 {@link #loops()}。 */
    public static LoopRegistry emptyLoops() {
        return new LoopRegistry();
    }

    /** 已装好 6 个内置 Loop 的注册表。 */
    public static LoopRegistry loops() {
        return BuiltinLoops.defaultRegistry();
    }

    public static ModelRegistry models() {
        return new ModelRegistry();
    }

    /** 扫描若干对象上的 {@code @LlmTool} 方法，组成工具集。 */
    public static ToolRegistry tools(Object... beans) {
        return ToolRegistry.of(ToolScanner.scanAll(beans));
    }

    // ---------- 模型 ----------

    /** 默认传输层：JDK HttpClient，零第三方依赖。 */
    public static HttpTransport transport() {
        return new JdkHttpTransport();
    }

    public static LlmModel openAi(ModelConfig config) {
        return new OpenAiModel(config);
    }

    public static LlmModel openAi(ModelConfig config, HttpTransport transport) {
        return new OpenAiModel(config, transport);
    }

    public static LlmModel anthropic(ModelConfig config) {
        return new AnthropicModel(config);
    }

    public static LlmModel anthropic(ModelConfig config, HttpTransport transport) {
        return new AnthropicModel(config, transport);
    }

    public static LlmModel gemini(ModelConfig config) {
        return new GeminiModel(config);
    }

    public static LlmModel gemini(ModelConfig config, HttpTransport transport) {
        return new GeminiModel(config, transport);
    }

    /** 按 {@link ModelConfig#protocol()} 自动选择协议实现。 */
    public static LlmModel model(ModelConfig config, HttpTransport transport) {
        return com.benxin.llm.core.model.ModelFactory.create(config, transport);
    }

    /** 直接用某个协议编解码器装配模型 —— 自定义协议时的通用入口。 */
    public static LlmModel withCodec(ModelConfig config,
                                     com.benxin.llm.core.protocol.ProtocolCodec codec,
                                     HttpTransport transport) {
        return new HttpLlmModel(config, codec, transport);
    }
}