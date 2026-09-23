package com.benxin.llm.core.model.impl;

import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.Codecs;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;

/**
 * Google Gemini 协议预设（{@code POST /v1beta/models/{model}:generateContent}）。
 *
 * <p>Gemini 与前两者的差异最大：模型 id 进的是 URL 路径、{@code contents}/{@code parts} 结构、
 * {@code role=model} 而非 {@code assistant}、工具用 {@code functionDeclarations}。
 * 这些差异同样被 {@code GeminiCodec} 完全吸收，本类只负责协议预设与能力声明。</p>
 */
public class GeminiModel extends HttpLlmModel {

    /**
     * 推荐能力声明，依据如下：
     * <ul>
     *   <li>{@code streaming=true}：{@code :streamGenerateContent} 是官方并列端点，本心统一走 SSE 解码。</li>
     *   <li>{@code toolCalling=true}：{@code tools[].functionDeclarations} + {@code functionCall} 为标准形态。</li>
     *   <li>{@code parallelToolCalls=true}：单轮可返回多个 {@code functionCall} part（并行函数调用）。</li>
     *   <li>{@code vision=true}：{@code inline_data} 原生支持图像等多模态输入。</li>
     *   <li>{@code thinking=true}：2.5 系列起原生支持思考（{@code thinkingConfig} + thought part）。</li>
     *   <li>{@code maxContextTokens=1_000_000}：1.5 / 2.x Pro 的百万级上下文窗口。</li>
     * </ul>
     * 注意：Gemini 系列跨度大（Flash 与 Pro 的窗口不同），此处取系列上限；
     * 需要精确控制时请用三参构造器传入自己的声明。
     */
    public static final ModelCapabilities RECOMMENDED_CAPABILITIES = ModelCapabilities.builder()
            .streaming(true)
            .toolCalling(true)
            .parallelToolCalls(true)
            .vision(true)
            .thinking(true)
            .maxContextTokens(1_000_000)
            .build();

    /**
     * 主构造器：显式指定能力声明。
     *
     * @param config       模型端点配置
     * @param transport    HTTP 传输实现
     * @param capabilities 能力声明；传 null 时兜底为默认值
     */
    public GeminiModel(ModelConfig config, HttpTransport transport, ModelCapabilities capabilities) {
        super(config, resolveCodec(), transport, capabilities);
    }

    /** 使用本预设的推荐能力声明。 */
    public GeminiModel(ModelConfig config, HttpTransport transport) {
        this(config, transport, RECOMMENDED_CAPABILITIES);
    }

    /** 最省事的构造方式：使用 JDK 自带 HttpClient 作为传输层。 */
    public GeminiModel(ModelConfig config) {
        this(config, new JdkHttpTransport());
    }

    /** 静态工厂，便于流式装配。 */
    public static GeminiModel of(ModelConfig config, HttpTransport transport) {
        return new GeminiModel(config, transport);
    }

    @Override
    public String toString() {
        return "GeminiModel(" + config() + ")";
    }

    /** 见 {@link OpenAiModel} 中同名方法的说明：统一走 Codecs 反射加载，避免编译期耦合。 */
    private static ProtocolCodec resolveCodec() {
        try {
            return Codecs.builtin(Protocol.GEMINI).orElseThrow(() -> new ModelException(
                    "未找到 Gemini 协议编解码器（Codecs 中未注册 gemini），请确认 benxin-core 打包完整"));
        } catch (IllegalStateException e) {
            throw new ModelException("Gemini 协议编解码器加载失败: " + e.getMessage(), e);
        }
    }
}
