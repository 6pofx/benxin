package com.benxin.llm.core.model.impl;

import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.Codecs;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.openai.OpenAiCodec;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;

/**
 * OpenAI Chat Completions 协议预设（{@code POST /v1/chat/completions}）。
 *
 * <p>本类刻意保持"薄"：它只做两件事——把 {@link Protocol#OPENAI} 的编解码器接上、
 * 给出一份推荐能力声明。真正的报文处理全在 {@code OpenAiCodec} 与 {@link HttpLlmModel} 里，
 * 因此"换厂商"只需改 {@link ModelConfig#baseUrl()}，不需要新的模型类。</p>
 *
 * <p>因为该协议被 DeepSeek、通义千问、Kimi、GLM、vLLM、Ollama、OneAPI 等大量厂商兼容，
 * 这个预设的实际覆盖面远大于"OpenAI 官方"。</p>
 */
public class OpenAiModel extends HttpLlmModel {

    /**
     * 推荐能力声明。
     *
     * <p>真正的定义在 {@link OpenAiCodec#RECOMMENDED_CAPABILITIES} ——
     * "这套协议支持什么"属于协议级知识，归 Codec 所有；
     * 这里保留同名常量只是为了兼容既有调用方。</p>
     */
    public static final ModelCapabilities RECOMMENDED_CAPABILITIES = OpenAiCodec.RECOMMENDED_CAPABILITIES;

    /**
     * 主构造器：显式指定能力声明。
     *
     * @param config       模型端点配置
     * @param transport    HTTP 传输实现（可换成 OkHttp / WebClient / 测试假实现）
     * @param capabilities 能力声明；传 null 时由 {@link HttpLlmModel} 兜底为默认值
     */
    public OpenAiModel(ModelConfig config, HttpTransport transport, ModelCapabilities capabilities) {
        super(config, resolveCodec(), transport, capabilities);
    }

    /** 使用本预设的推荐能力声明。 */
    public OpenAiModel(ModelConfig config, HttpTransport transport) {
        this(config, transport, RECOMMENDED_CAPABILITIES);
    }

    /** 最省事的构造方式：使用 JDK 自带 HttpClient 作为传输层。 */
    public OpenAiModel(ModelConfig config) {
        this(config, new JdkHttpTransport());
    }

    /** 静态工厂，便于流式装配。 */
    public static OpenAiModel of(ModelConfig config, HttpTransport transport) {
        return new OpenAiModel(config, transport);
    }

    @Override
    public String toString() {
        return "OpenAiModel(" + config() + ")";
    }

    /**
     * 通过 {@link Codecs} 反射加载编解码器，而不是直接 {@code new OpenAiCodec()}。
     *
     * <p>这样做有两个好处：一是装配入口唯一，"用户自定义 Codec 覆盖内置实现"只需改 Codecs 的注册表；
     * 二是 core 与各协议实现之间不产生编译期依赖，协议实现可以独立演进。</p>
     */
    private static ProtocolCodec resolveCodec() {
        try {
            return Codecs.builtin(Protocol.OPENAI).orElseThrow(() -> new ModelException(
                    "未找到 OpenAI 协议编解码器（Codecs 中未注册 openai），请确认 benxin-core 打包完整"));
        } catch (IllegalStateException e) {
            // Codecs 用反射实例化，类缺失/无无参构造时抛的是 IllegalStateException；
            // 这里翻译成 ModelException，让上层只需处理一种异常类型。
            throw new ModelException("OpenAI 协议编解码器加载失败: " + e.getMessage(), e);
        }
    }
}
