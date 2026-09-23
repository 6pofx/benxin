package com.benxin.llm.core.model;

import com.benxin.llm.core.model.impl.AnthropicModel;
import com.benxin.llm.core.model.impl.GeminiModel;
import com.benxin.llm.core.model.impl.OpenAiModel;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.protocol.Protocol;
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
 *   <li><b>按协议选预设</b>：{@code openai / anthropic / gemini} 分别装配成
 *       {@link OpenAiModel} / {@link AnthropicModel} / {@link GeminiModel}。
 *       加新协议时这里加一个分支即可，调用方（自动配置、测试、业务代码）不变。</li>
 *   <li><b>兑现 {@link ModelConfig#maxRetries()}</b>：配置里写了重试次数就自动包一层
 *       {@link RetryingLlmModel}。放在工厂里做而不是让每个调用方自己包，
 *       是为了保证"配置即行为"——用户改一行配置就能拿到重试，而装饰器顺序只有一处定义。</li>
 * </ol>
 *
 * <p>本类是无状态工具类，可以安全地在多线程间共享。</p>
 */
public final class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    /** 工具类不允许实例化。 */
    private ModelFactory() {
        throw new AssertionError("ModelFactory 是工具类，不应被实例化");
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
        Objects.requireNonNull(config, "config 不能为 null");
        HttpTransport effective = transport == null ? new JdkHttpTransport() : transport;
        LlmModel model = createByProtocol(config, effective);

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
            LlmModel model = create(config, transport);
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
        Map<String, LlmModel> models = createAll(configs, transport);
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

    /** 协议 → 预设的分派。协议枚举本身已覆盖内置三种，default 分支用于防御未来新增枚举值。 */
    private static LlmModel createByProtocol(ModelConfig config, HttpTransport transport) {
        Protocol protocol = config.protocol();
        if (protocol == null) {
            throw new ModelException("模型 [" + config.name()
                    + "] 未指定协议，可选: openai / anthropic / gemini");
        }
        return switch (protocol) {
            case OPENAI -> new OpenAiModel(config, transport);
            case ANTHROPIC -> new AnthropicModel(config, transport);
            case GEMINI -> new GeminiModel(config, transport);
            default -> throw new ModelException("模型 [" + config.name() + "] 使用了不支持的协议 ["
                    + protocol.id() + "]，本心内置支持: openai / anthropic / gemini");
        };
    }

    private static String firstKey(Map<String, LlmModel> models) {
        return models.isEmpty() ? null : models.keySet().iterator().next();
    }
}
