package com.benxin.llm.core.model.impl;

import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.Codecs;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.anthropic.AnthropicCodec;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;

/**
 * Anthropic Messages 协议预设（{@code POST /v1/messages}）。
 *
 * <p>与 {@link OpenAiModel} 一样，本类只是"协议预设 + 能力声明"的薄壳：
 * system 提顶层、content block、{@code tool_use}/{@code tool_result} 等协议差异
 * 全部由 {@code AnthropicCodec} 吸收，调用方看到的仍是统一的 {@code ChatRequest}/{@code ChatResponse}。</p>
 */
public class AnthropicModel extends HttpLlmModel {

    /**
     * 推荐能力声明。
     *
     * <p>真正的定义在 {@link AnthropicCodec#RECOMMENDED_CAPABILITIES} ——
     * "这套协议支持什么"属于协议级知识，归 Codec 所有；
     * 这里保留同名常量只是为了兼容既有调用方。</p>
     */
    public static final ModelCapabilities RECOMMENDED_CAPABILITIES = AnthropicCodec.RECOMMENDED_CAPABILITIES;

    /**
     * 主构造器：显式指定能力声明。
     *
     * @param config       模型端点配置
     * @param transport    HTTP 传输实现
     * @param capabilities 能力声明；传 null 时兜底为默认值
     */
    public AnthropicModel(ModelConfig config, HttpTransport transport, ModelCapabilities capabilities) {
        super(config, resolveCodec(), transport, capabilities);
    }

    /** 使用本预设的推荐能力声明。 */
    public AnthropicModel(ModelConfig config, HttpTransport transport) {
        this(config, transport, RECOMMENDED_CAPABILITIES);
    }

    /** 最省事的构造方式：使用 JDK 自带 HttpClient 作为传输层。 */
    public AnthropicModel(ModelConfig config) {
        this(config, new JdkHttpTransport());
    }

    /** 静态工厂，便于流式装配。 */
    public static AnthropicModel of(ModelConfig config, HttpTransport transport) {
        return new AnthropicModel(config, transport);
    }

    @Override
    public String toString() {
        return "AnthropicModel(" + config() + ")";
    }

    /** 见 {@link OpenAiModel} 中同名方法的说明：统一走 Codecs 反射加载，避免编译期耦合。 */
    private static ProtocolCodec resolveCodec() {
        try {
            return Codecs.builtin(Protocol.ANTHROPIC).orElseThrow(() -> new ModelException(
                    "未找到 Anthropic 协议编解码器（Codecs 中未注册 anthropic），请确认 benxin-core 打包完整"));
        } catch (IllegalStateException e) {
            throw new ModelException("Anthropic 协议编解码器加载失败: " + e.getMessage(), e);
        }
    }
}
