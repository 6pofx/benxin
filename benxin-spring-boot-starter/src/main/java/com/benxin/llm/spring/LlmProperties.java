package com.benxin.llm.spring;

import com.benxin.llm.core.model.ModelConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code llm.*} 配置。
 *
 * <p>本类刻意只做"配置 → 领域对象"的翻译，不含任何装配逻辑：
 * {@link #toModelConfig(String)} 交给 {@code ModelConfig}，
 * 沙箱参数交给 {@code SandboxConfig}。这样即便脱离 Spring，同一套配置语义也能复用。</p>
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 总开关；置 false 可整体关停本心的自动配置。 */
    private boolean enabled = true;

    /** 默认模型名（对应 {@link #models} 的 key）。 */
    private String defaultModel;

    /** 模型端点定义。 */
    private Map<String, ModelProperties> models = new LinkedHashMap<>();

    /** Agent 全局默认值。 */
    private final AgentProperties agent = new AgentProperties();

    /** 内置工具与沙箱。 */
    private final ToolProperties tools = new ToolProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, ModelProperties> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelProperties> models) {
        this.models = models == null ? new LinkedHashMap<>() : models;
    }

    public AgentProperties getAgent() {
        return agent;
    }

    public ToolProperties getTools() {
        return tools;
    }

    /** 单个模型端点的配置。 */
    public static class ModelProperties {

        /** 协议：{@code openai} / {@code anthropic} / {@code gemini}。 */
        private String protocol = "openai";

        private String baseUrl;
        private String apiKey;

        /** 上游模型 id，例如 {@code deepseek-chat}、{@code claude-sonnet-4-5}。 */
        private String model;

        private Map<String, String> headers = new LinkedHashMap<>();

        /** 直接透传到请求体顶层的厂商私有参数。 */
        private Map<String, Object> extra = new LinkedHashMap<>();

        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration streamReadTimeout;

        /** 瞬时错误（限流 / 5xx / 网络）自动重试次数；0 表示不重试。 */
        private int maxRetries = 2;

        private Double temperature;
        private Integer maxTokens;

        /** 是否为全局默认模型。 */
        private boolean primary;

        /** 同为 primary 时按此排序，值小者胜出。 */
        private int order;

        public ModelConfig toModelConfig(String name) {
            return ModelConfig.builder(name)
                    .protocol(protocol)
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(model == null || model.isBlank() ? name : model)
                    .headers(headers)
                    .extra(extra)
                    .connectTimeout(connectTimeout)
                    .readTimeout(readTimeout)
                    .streamReadTimeout(streamReadTimeout)
                    .maxRetries(maxRetries)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, Object> extra) {
            this.extra = extra == null ? new LinkedHashMap<>() : extra;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getStreamReadTimeout() {
            return streamReadTimeout;
        }

        public void setStreamReadTimeout(Duration streamReadTimeout) {
            this.streamReadTimeout = streamReadTimeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public boolean isPrimary() {
            return primary;
        }

        public void setPrimary(boolean primary) {
            this.primary = primary;
        }

        public int getOrder() {
            return order;
        }

        public void setOrder(int order) {
            this.order = order;
        }
    }

    /** Agent 全局默认值；{@code @LlmAgent} 上未显式指定的属性回落到这里。 */
    public static class AgentProperties {

        /** 默认 Loop 名。 */
        private String defaultLoop = "dsh-minimal";

        private int maxSteps = 24;

        private boolean stream = false;

        private boolean memory = true;

        /** 采样温度；负值表示不下发。 */
        private double temperature = -1;

        /** 单次回复最大 token；负值表示不下发。 */
        private int maxTokens = -1;

        /** 全局兜底系统提示词。 */
        private String systemPrompt;

        /** 是否挂载内置日志监听器。 */
        private boolean loggingListener = true;

        /** 默认审批策略：{@code never} / {@code on-request} / {@code always} / {@code on-failure}。 */
        private String approvalPolicy = "on-request";

        /** 单次运行的最大步数上限（防止误配置成无限循环）。 */
        private int hardMaxSteps = 200;

        public String getDefaultLoop() {
            return defaultLoop;
        }

        public void setDefaultLoop(String defaultLoop) {
            this.defaultLoop = defaultLoop;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }

        public boolean isMemory() {
            return memory;
        }

        public void setMemory(boolean memory) {
            this.memory = memory;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public boolean isLoggingListener() {
            return loggingListener;
        }

        public void setLoggingListener(boolean loggingListener) {
            this.loggingListener = loggingListener;
        }

        public String getApprovalPolicy() {
            return approvalPolicy;
        }

        public void setApprovalPolicy(String approvalPolicy) {
            this.approvalPolicy = approvalPolicy;
        }

        public int getHardMaxSteps() {
            return hardMaxSteps;
        }

        public void setHardMaxSteps(int hardMaxSteps) {
            this.hardMaxSteps = hardMaxSteps;
        }
    }

    /**
     * 内置工具与沙箱配置。
     *
     * <p><b>安全默认值</b>：内置工具整体关闭、写文件关闭、执行命令关闭。
     * 这些工具能真实读写本地文件、执行本地命令，默认开启等于把宿主机交给模型，
     * 因此必须显式三连开启（{@code builtin-enabled} + {@code allow-write} / {@code allow-exec}）。</p>
     */
    public static class ToolProperties {

        /** 是否装配内置文件/命令工具。默认 false。 */
        private boolean builtinEnabled = false;

        /** 沙箱围栏根目录；留空取进程工作目录。 */
        private String workdir;

        /** 是否允许写文件（write / edit）。默认 false。 */
        private boolean allowWrite = false;

        /** 是否允许执行命令（bash）。默认 false。 */
        private boolean allowExec = false;

        /** 命令白名单前缀；非空时命令必须以此开头。 */
        private List<String> allowedCommands = new ArrayList<>();

        /** 命令黑名单前缀；命中即拒绝。 */
        private List<String> deniedCommands = new ArrayList<>();

        /** 显式启用的工具名；为空表示按 allow-write / allow-exec 推导。 */
        private List<String> enabled = new ArrayList<>();

        private Duration execTimeout = Duration.ofSeconds(60);

        private int maxOutputChars = 30_000;

        private long maxFileBytes = 1024L * 1024L;

        public boolean isBuiltinEnabled() {
            return builtinEnabled;
        }

        public void setBuiltinEnabled(boolean builtinEnabled) {
            this.builtinEnabled = builtinEnabled;
        }

        public String getWorkdir() {
            return workdir;
        }

        public void setWorkdir(String workdir) {
            this.workdir = workdir;
        }

        public boolean isAllowWrite() {
            return allowWrite;
        }

        public void setAllowWrite(boolean allowWrite) {
            this.allowWrite = allowWrite;
        }

        public boolean isAllowExec() {
            return allowExec;
        }

        public void setAllowExec(boolean allowExec) {
            this.allowExec = allowExec;
        }

        public List<String> getAllowedCommands() {
            return allowedCommands;
        }

        public void setAllowedCommands(List<String> allowedCommands) {
            this.allowedCommands = allowedCommands == null ? new ArrayList<>() : allowedCommands;
        }

        public List<String> getDeniedCommands() {
            return deniedCommands;
        }

        public void setDeniedCommands(List<String> deniedCommands) {
            this.deniedCommands = deniedCommands == null ? new ArrayList<>() : deniedCommands;
        }

        public List<String> getEnabled() {
            return enabled;
        }

        public void setEnabled(List<String> enabled) {
            this.enabled = enabled == null ? new ArrayList<>() : enabled;
        }

        public Duration getExecTimeout() {
            return execTimeout;
        }

        public void setExecTimeout(Duration execTimeout) {
            this.execTimeout = execTimeout;
        }

        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        public void setMaxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
    }
}