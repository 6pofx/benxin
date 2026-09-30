package com.benxin.llm.core.protocol;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议标识 —— 可扩展的标识类型，而不是封闭枚举。
 *
 * <p>之所以是"协议"而不是"厂商"：同一协议被大量厂商兼容复用，例如
 * OpenAI Chat Completions 协议同时服务 OpenAI、DeepSeek、通义千问、Kimi、
 * GLM、vLLM、Ollama、OneAPI 等。</p>
 *
 * <h2>为什么不用 enum</h2>
 *
 * <p>本心上一个版本这里是一个只有三个取值的 {@code enum}，于是
 * {@code Protocol.from("bedrock")} 会直接抛异常、配置里写 {@code protocol: bedrock}
 * 会让应用起不来 —— 也就是"加第四种协议"这条路在第一行就断了，
 * 与"一切皆可插拔"的总基调相矛盾。</p>
 *
 * <p>改成标识类型之后：<b>任意 id 都能构造出一个 Protocol</b>，它是否真的可用
 * 由 {@link ProtocolRegistry} 里有没有对应的 {@link ProtocolCodec} 决定。
 * "协议名写错"因此从"绑定期异常"变成了"装配期的可读报错，并列出所有已注册协议"，
 * 这既能容纳自定义协议，又没有丢掉快速失败。</p>
 *
 * <h2>相等性与标识</h2>
 *
 * <p>实例按 id <b>驻留</b>（intern）：{@code Protocol.of("openai") == Protocol.OPENAI}
 * 恒为真，因此既可以用 {@code ==} 也可以用 {@link #equals(Object)}。
 * 归一化只做 {@code trim} + 转小写，<b>不做</b>分隔符替换 ——
 * 否则 {@code my-protocol} 会被改写成 {@code my_protocol}，把用户自定义的 id 弄坏。</p>
 *
 * <p><b>驻留表的规模：</b>它没有任何淘汰策略，因此 {@link #of(String)} 的入参 id 集合
 * 应当是<b>有限</b>的。框架自身满足这一点：高频路径走的是
 * {@link ProtocolRegistry#find(Protocol)} 这个类型化重载，字符串入口只有
 * {@code ModelConfig.Builder#protocol(String)}（由配置项驱动，数量有界）与
 * {@link ProtocolRegistry} 的 {@code register/find(String)}（使用者程序化调用）。
 * 但如果在长驻服务里按请求构造 {@code ModelConfig}（例如多租户网关逐租户拼配置），
 * 每个不同的协议字符串都会永久留在表里 —— 那种用法应当自己把协议 id 收敛成有限集合。</p>
 *
 * <h2>空白 id 不再被当成 openai</h2>
 *
 * <p>"缺省即 openai"是配置项 {@code llm.models.<key>.protocol} 的默认值，
 * 因此它由<b>配置绑定层</b>（{@code ModelConfig.Builder#protocol(String)}）兜底，
 * 而不是由这个标识类型替调用方猜。{@link #of(String)} 对空白入参直接抛
 * {@link IllegalArgumentException}：否则"我没填名字"与"我就是 openai"在类型层面
 * 无法区分，一个 {@code protocol()} 返回空串的 {@link ProtocolCodec} 会
 * <b>静默占住内置 openai 那一格</b>，全应用的 OpenAI 通道被悄悄换掉。</p>
 */
public final class Protocol {

    /** {@code POST /v1/chat/completions} —— 事实上的行业标准。 */
    public static final Protocol OPENAI = new Protocol("openai");

    /** {@code POST /v1/messages} —— Anthropic Messages API。 */
    public static final Protocol ANTHROPIC = new Protocol("anthropic");

    /** {@code POST /v1beta/models/{model}:generateContent} —— Google Gemini。 */
    public static final Protocol GEMINI = new Protocol("gemini");

    /**
     * {@code POST /v1/responses} —— OpenAI Responses API。
     *
     * <p>与 Chat Completions 是两套不同的报文结构（{@code input} item 列表、
     * 顶层 {@code instructions}、扁平的 {@code tools[]}、命名 SSE 事件），
     * 因此作为独立协议而不是前者的开关。</p>
     */
    public static final Protocol RESPONSES = new Protocol("responses");

    /** 本心内置的协议，顺序即文档与报错信息里的展示顺序。 */
    private static final List<Protocol> BUILTINS = List.of(OPENAI, ANTHROPIC, GEMINI, RESPONSES);

    /** id → 规范实例。用于实例驻留，保证 {@code ==} 语义成立。 */
    private static final Map<String, Protocol> INTERNED = new ConcurrentHashMap<>();

    static {
        for (Protocol protocol : BUILTINS) {
            INTERNED.put(protocol.id, protocol);
        }
    }

    private final String id;

    private Protocol(String id) {
        this.id = id;
    }

    /** 协议标识，例如 {@code openai} / {@code anthropic} / {@code gemini} / {@code responses}。 */
    public String id() {
        return id;
    }

    /**
     * 按 id 取得协议标识；<b>任意非空 id 都合法</b>，不会因"不认识"而抛异常。
     *
     * <p>该 id 是否真的可用，取决于 {@link ProtocolRegistry} 里是否注册了对应的
     * {@link ProtocolCodec}；未注册时会在装配模型时报出可读错误并列出所有可选协议。</p>
     *
     * <p><b>空白入参直接拒绝</b>：{@code null} / 空串 / 纯空白都抛
     * {@link IllegalArgumentException}。"缺省即 {@link #OPENAI}"是配置项
     * {@code llm.models.<key>.protocol} 的默认值，由 {@code ModelConfig.Builder#protocol(String)}
     * 兜底；标识类型不再替调用方猜默认值 —— 否则一个 {@code protocol()} 返回空串的
     * {@link ProtocolCodec} 会静默占住内置 {@code openai} 那一格。</p>
     *
     * @throws IllegalArgumentException 当 {@code value} 为 null 或空白
     */
    public static Protocol of(String value) {
        String normalized = normalize(value);
        Protocol known = INTERNED.get(normalized);
        return known != null ? known : INTERNED.computeIfAbsent(normalized, Protocol::new);
    }

    /**
     * {@link #of(String)} 的等价别名。
     *
     * <p>保留这个名字是为了兼容既有调用方。注意它的语义已变化：
     * 从前是"解析并校验，未知即抛"，现在是"归一化，未知也接受"。</p>
     */
    public static Protocol from(String value) {
        return of(value);
    }

    /** 内置协议列表（不可变，顺序稳定）。 */
    public static List<Protocol> builtins() {
        return BUILTINS;
    }

    /** 是否为本心内置协议。 */
    public static boolean isBuiltin(Protocol protocol) {
        return protocol != null && BUILTINS.contains(protocol);
    }

    /** 内置协议的 id 列表，用于报错信息与文档。 */
    public static List<String> builtinIds() {
        return BUILTINS.stream().map(Protocol::id).toList();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            // 不在这里回落到 openai：那会让"名字没填"与"我就是 openai"在类型层面无法区分，
            // 于是一个 protocol() 返回空串的 Codec 会静默顶掉内置 openai（F-46）。
            // 配置项的"缺省即 openai"由 ModelConfig.Builder#protocol(String) 兜底。
            throw new IllegalArgumentException(
                    "协议 id 不能为空（传入：" + (value == null ? "null" : "\"" + value + "\"") + "）。"
                            + "如果这是配置项 llm.models.<key>.protocol 缺省，请显式写 openai；"
                            + "如果这是 ProtocolCodec#protocol() 的返回值，请让它返回真实协议名 —— "
                            + "返回空串会（在修复前）静默占用内置 openai 那一格");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Protocol protocol && id.equals(protocol.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
