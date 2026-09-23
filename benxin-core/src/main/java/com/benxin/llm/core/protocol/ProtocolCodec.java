package com.benxin.llm.core.protocol;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelConfig;

import java.util.Map;

/**
 * 协议编解码器 —— 本心"一切可替换"的关键切面之一。
 *
 * <p>想让本心支持一个新协议（例如 AWS Bedrock、Cohere、Ollama 原生 API），
 * 只需实现本接口并注册为 bean，无需改动任何调用方代码。</p>
 */
public interface ProtocolCodec {

    /** 本编解码器对应的协议。 */
    Protocol protocol();

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