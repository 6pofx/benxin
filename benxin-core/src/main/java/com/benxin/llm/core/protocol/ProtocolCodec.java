package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;

import java.util.Map;

/**
 * 协议编解码器 —— 本心"一切可插拔"的关键切面之一。
 *
 * <p>想让本心支持一个新协议（例如 AWS Bedrock、Cohere、Ollama 原生 API），
 * 只需实现本接口、把 {@link #protocol()} 指向一个自定义协议名，
 * 并把它交给容器（或注册进 {@link ProtocolRegistry}）——
 * 之后在 {@code llm.models.*.protocol} 里写那个名字即可，调用方代码一行都不用改。</p>
 */
public interface ProtocolCodec {

    /** 本编解码器对应的协议；自定义协议用 {@link Protocol#of(String)} 构造。 */
    Protocol protocol();

    /**
     * 该协议的能力声明，决定 Loop 如何与模型交互
     * （是否走流式、能否下发工具、窗口多大）。
     *
     * <p>放在 Codec 而不是模型预设里，是因为"这个协议长什么样"与
     * "这条线上限多少 token"本来就是协议级知识；自定义协议也因此不必再写一个模型类，
     * 只实现 Codec 就能得到一份正确的默认能力。</p>
     *
     * <p>默认值 {@link ModelCapabilities#DEFAULT} 是偏保守的通用声明；
     * 若你的协议不支持流式或工具调用，务必覆写它 —— Loop 会据此改变交互方式。</p>
     */
    default ModelCapabilities capabilities() {
        return ModelCapabilities.DEFAULT;
    }

    /** 请求地址（含路径与查询串）。 */
    String endpoint(ModelConfig config, ChatRequest request);

    /** 附加请求头（认证、版本号等）。 */
    Map<String, String> headers(ModelConfig config);

    /** 把统一请求编码为协议 JSON 请求体。 */
    String encode(ChatRequest request, ModelConfig config);

    /** 把协议 JSON 响应体解码为统一响应。 */
    ChatResponse decode(String responseBody, ModelConfig config);

    /** 创建流式解码器。 */
    StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler);
}