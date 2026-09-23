package com.benxin.llm.core.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内置协议编解码器工厂。
 *
 * <p>这里刻意不做成 static final 单例，而是每次新建实例，避免用户自定义 Codec
 * 与内置 Codec 之间产生状态耦合。</p>
 */
public final class Codecs {

    private static final Map<String, java.util.function.Supplier<ProtocolCodec>> BUILTIN = new LinkedHashMap<>();

    static {
        // 内置实现由各自协议包提供；用反射注册可让 core 在没有该实现时仍可编译通过
        // （实际实现始终随 core 一起发布，这里只是保持装配顺序显式化）。
        register(Protocol.OPENAI, "com.benxin.llm.core.protocol.openai.OpenAiCodec");
        register(Protocol.ANTHROPIC, "com.benxin.llm.core.protocol.anthropic.AnthropicCodec");
        register(Protocol.GEMINI, "com.benxin.llm.core.protocol.gemini.GeminiCodec");
    }

    private Codecs() {
    }

    private static void register(Protocol protocol, String className) {
        BUILTIN.put(protocol.id(), () -> instantiate(className));
    }

    private static ProtocolCodec instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            return (ProtocolCodec) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("协议实现加载失败: " + className, e);
        }
    }

    /** 按协议取内置实现；未知协议返回空。 */
    public static Optional<ProtocolCodec> builtin(Protocol protocol) {
        java.util.function.Supplier<ProtocolCodec> supplier = BUILTIN.get(protocol.id());
        return supplier == null ? Optional.empty() : Optional.of(supplier.get());
    }
}