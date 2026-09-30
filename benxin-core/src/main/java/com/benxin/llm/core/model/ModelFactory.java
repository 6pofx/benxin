package com.benxin.llm.core.model;

import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模型装配工厂：把"一份配置 + 一个传输实现"变成一个可用的 {@link LlmModel}。
 *
 * <p>它承担两个装配职责：</p>
 * <ol>
 *   <li><b>按协议解析编解码器</b>：从 {@link ProtocolRegistry} 里取
 *       {@link ProtocolCodec}，再连同配置与传输层装成 {@link HttpLlmModel}。
 *       内置四协议在默认注册表里预先登记，使用者注册自己的 Codec 即可扩展或覆盖 ——
 *       这里<b>没有任何 {@code switch}</b>，因为协议集合是运行期可变的。</li>
 *   <li><b>兑现 {@link ModelConfig#maxRetries()}</b>：配置里写了重试次数就自动包一层
 *       {@link RetryingLlmModel}。放在工厂里做而不是让每个调用方自己包，
 *       是为了保证"配置即行为"——用户改一行配置就能拿到重试，而装饰器顺序只有一处定义。</li>
 * </ol>
 *
 * <p>本类是无状态工具类，可以安全地在多线程间共享。</p>
 */
public final class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    /**
     * 未显式传入注册表时使用的默认注册表：只含本心内置四协议。
     *
     * <p>刻意<b>不</b>做成"全局可变单例"：那样用户的注册会泄漏到所有调用方，
     * 测试之间也会互相污染。Spring 路径会传自己的注册表（内置 + 容器里的
     * {@code ProtocolCodec} bean），裸 core 路径用这一份即可。</p>
     */
    private static final ProtocolRegistry DEFAULT_REGISTRY = ProtocolRegistry.withBuiltins();

    /** 工具类不允许实例化。 */
    private ModelFactory() {
        throw new AssertionError("ModelFactory 是工具类，不应被实例化");
    }

    /** 默认协议注册表（内置四协议，不可被外部修改）。 */
    public static ProtocolRegistry defaultRegistry() {
        return DEFAULT_REGISTRY;
    }

    /**
     * 按配置装配模型；{@code maxRetries > 0} 时自动套上重试装饰器。
     *
     * @param config    模型配置，不能为 null
     * @param transport HTTP 传输实现；为 null 时退化为 {@link JdkHttpTransport}，
     *                  便于"只想快速跑起来"的场景，也让调用方不必到处判空
     * @return 装配好的模型（可能是被 {@link RetryingLlmModel} 包装后的）
     */
    public static LlmModel create(ModelConfig config, HttpTransport transport) {
        return create(config, transport, DEFAULT_REGISTRY);
    }

    /**
     * 按配置装配模型，并使用指定的协议注册表解析编解码器。
     *
     * @param registry 协议注册表；为 null 时退化为默认注册表
     */
    public static LlmModel create(ModelConfig config, HttpTransport transport, ProtocolRegistry registry) {
        Objects.requireNonNull(config, "config 不能为 null");
        HttpTransport effective = transport == null ? new JdkHttpTransport() : transport;
        ProtocolRegistry effectiveRegistry = registry == null ? DEFAULT_REGISTRY : registry;
        LlmModel model = createByProtocol(config, effective, effectiveRegistry);

        int maxRetries = config.maxRetries();
        if (maxRetries <= 0) {
            return model;
        }
        if (log.isDebugEnabled()) {
            log.debug("模型 [{}] 配置了 maxRetries={}，自动包装 RetryingLlmModel", config.name(), maxRetries);
        }
        // 退避参数沿用装饰器的默认值：配置里只暴露"重试几次"，
        // 退避曲线属于实现细节，需要精细控制时调用方可以自己 new RetryingLlmModel。
        return new RetryingLlmModel(model, maxRetries,
                RetryingLlmModel.DEFAULT_INITIAL_BACKOFF_MILLIS,
                RetryingLlmModel.DEFAULT_MULTIPLIER);
    }

    /** 使用 JDK 自带 HttpClient 的便捷装配。 */
    public static LlmModel create(ModelConfig config) {
        return create(config, new JdkHttpTransport());
    }

    /** 使用 JDK 自带 HttpClient，并指定协议注册表。 */
    public static LlmModel create(ModelConfig config, ProtocolRegistry registry) {
        return create(config, new JdkHttpTransport(), registry);
    }

    /**
     * 批量装配。
     *
     * <p>返回 {@link LinkedHashMap}：模型顺序在日志、文档与"默认模型取第一个"的语义里都会体现，
     * 因此刻意保留配置的插入顺序（而不是用 HashMap 打乱它）。</p>
     *
     * <p>键取配置 Map 的键而非 {@link ModelConfig#name()}：键才是用户在
     * {@code @LlmAgent(model = "...")} 里写的名字，二者不一致时以用户实际引用的那个为准。</p>
     *
     * @param configs   配置集合，可为 null/空（返回空 Map）
     * @param transport HTTP 传输实现，可为 null（退化为 JDK 实现）
     */
    public static Map<String, LlmModel> createAll(Map<String, ModelConfig> configs, HttpTransport transport) {
        return createAll(configs, transport, DEFAULT_REGISTRY);
    }

    /** 批量装配，并使用指定的协议注册表。 */
    public static Map<String, LlmModel> createAll(Map<String, ModelConfig> configs, HttpTransport transport,
                                                  ProtocolRegistry registry) {
        Map<String, LlmModel> models = new LinkedHashMap<>();
        if (configs == null || configs.isEmpty()) {
            return models;
        }
        for (Map.Entry<String, ModelConfig> entry : configs.entrySet()) {
            String key = entry.getKey();
            ModelConfig config = entry.getValue();
            if (config == null) {
                // 同名配置缺失属于配置错误，静默跳过会让人排查半天，直接失败更快
                throw new ModelException("模型配置 [" + key + "] 为 null，请检查 llm.models." + key + " 是否配置完整");
            }
            LlmModel model = create(config, transport, registry);
            models.put(key == null || key.isBlank() ? model.name() : key, model);
        }
        return models;
    }

    /**
     * 装配并注册到 {@link ModelRegistry}。
     *
     * @param configs     配置集合
     * @param transport   HTTP 传输实现，可为 null
     * @param defaultName 默认模型名；为 null 或空白时取第一个配置
     * @return 已注册且已设置默认模型的注册表
     */
    public static ModelRegistry registry(Map<String, ModelConfig> configs, HttpTransport transport,
                                         String defaultName) {
        return registry(configs, transport, defaultName, DEFAULT_REGISTRY);
    }

    /** 装配并注册到 {@link ModelRegistry}，并使用指定的协议注册表。 */
    public static ModelRegistry registry(Map<String, ModelConfig> configs, HttpTransport transport,
                                         String defaultName, ProtocolRegistry protocolRegistry) {
        Map<String, LlmModel> models = createAll(configs, transport, protocolRegistry);
        ModelRegistry registry = new ModelRegistry();
        registry.registerAll(models);

        String effective = defaultName == null || defaultName.isBlank() ? firstKey(models) : defaultName;
        if (effective != null) {
            if (!models.containsKey(effective)) {
                // 不抛异常：注册表在解析时会回退到第一个模型，先给出提示比直接启动失败更友好
                log.warn("默认模型 [{}] 不在已装配的模型中（已装配: {}），将按注册表的回退规则选取",
                        effective, models.keySet());
            }
            registry.setDefault(effective);
        }
        return registry;
    }

    /**
     * 协议 → 编解码器的解析。查不到就报出"未知协议 + 已注册协议清单 + 怎么加"。
     *
     * <p>报错时机是<b>装配期</b>（Spring 路径下即上下文启动期），
     * 而不是第一次请求时 —— 配置写错应当立刻可见。</p>
     */
    private static LlmModel createByProtocol(ModelConfig config, HttpTransport transport,
                                             ProtocolRegistry registry) {
        Protocol protocol = config.protocol();
        if (protocol == null) {
            throw new ModelException("模型 [" + config.name() + "] 未指定协议，可用协议: "
                    + registry.describe());
        }
        ProtocolCodec codec = registry.find(protocol).orElseThrow(() -> new ModelException(
                "模型 [" + config.name() + "] 使用了未知协议 [" + protocol.id() + "]，已注册的协议: "
                        + registry.describe()
                        + "。自定义协议请实现 ProtocolCodec 并把它交给容器（或注册进 ProtocolRegistry），"
                        + "再把 llm.models." + config.name() + ".protocol 改成该协议名。"));
        // 能力声明取自编解码器：它最清楚自己这套协议支持什么，自定义协议也因此不必再写一个模型类。
        return new HttpLlmModel(config, codec, transport, codec.capabilities());
    }

    private static String firstKey(Map<String, LlmModel> models) {
        return models.isEmpty() ? null : models.keySet().iterator().next();
    }
}
