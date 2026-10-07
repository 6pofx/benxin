# 本心 · benxin

> 一个 Spring Boot 插件：**声明式**地调用 LLM，并且**一切皆可插拔**。

模型、协议、循环、工具、上下文、记忆、提示词全都可以被换掉，
而调用方的写法不变。你写一个接口、打一个注解，剩下的交给容器。

```java
@LlmAgent(model = "deepseek", loop = "claude-code", tools = {WeatherTools.class})
public interface WeatherAssistant {
    String ask(String question);
}

@Autowired WeatherAssistant assistant;   // 注入即用
```

---

## 目录

- [核心主张：一切皆可插拔](#核心主张一切皆可插拔)
- [快速开始](#快速开始)
- [注解速查](#注解速查)
- [四种主流 LLM API 协议](#四种主流-llm-api-协议)
- [内置 Agent Loop](#内置-agent-loop)
- [工作流模式](#工作流模式)
- [工具](#工具)
- [替换任意一个切面](#替换任意一个切面)
- [可观测性](#可观测性)
- [模块结构](#模块结构)
- [运行示例](#运行示例)
- [设计取舍](#设计取舍)
- [版本与升级](#版本与升级)

---

## 核心主张：一切皆可插拔

框架里没有一个"必须用我的实现"的地方。每个环节都是 `接口 + 内置实现 + 覆盖点`：

| 切面 | 接口 | 内置实现 | 怎么换掉 |
|---|---|---|---|
| 模型 | `LlmModel` | `HttpLlmModel`（由协议 Codec 装配；`OpenAiModel` / `AnthropicModel` / `GeminiModel` 保留为便捷预设类） | 定义 `LlmModel` bean，或写 `llm.models.*` |
| 协议 | `ProtocolCodec` | `OpenAiCodec` / `AnthropicCodec` / `GeminiCodec` / `ResponsesApiCodec` | 注册 `ProtocolCodec` bean，配置里写 `protocol: <你的名字>`。见[加自己的协议](#加自己的协议) |
| 传输 | `HttpTransport` | `JdkHttpTransport`（零依赖） | 定义 `HttpTransport` bean（OkHttp / WebClient / 测试桩） |
| **循环** | `AgentLoop` | `dsh-minimal` `react` `claude-code` `codex` `plan-execute` `reflexion` `workflow` `staged` | `@LlmLoop("名字")` |
| **工具** | `ToolCallback` | 反射适配器 + 8 个内置工具 | `@LlmTool` 注解方法 |
| 上下文 | `ContextManager` | `DefaultContextManager` | 定义 bean |
| 压缩 | `ContextCompactor` | `SummarizingCompactor` / `SlidingWindowCompactor` | 定义 bean |
| 记忆 | `MemoryStore` | `InMemoryMemoryStore` | 定义 bean（Redis / JDBC） |
| 提示词 | `SystemPromptProvider` | `TemplateSystemPromptProvider` | 定义 bean（接提示词管理平台） |
| 拦截 | `AgentInterceptor` | 无（用户提供） | `@LlmGuard` 或注册 bean |
| 事件 | `AgentListener` | `LoggingListener` | 注册 bean |
| 沙箱 | `ToolSandbox` | `PathSandbox` / `CommandSandbox` | 定义 bean |
| 审批 | `ApprovalHandler` | `autoApprove` / `denyAll` | 定义 bean |
| 分词估算 | `TokenEstimator` | 中英混排启发式 | 定义 bean（接真实 tokenizer） |

**替换机制统一是**：定义一个同类型 bean。所有**可替换的组件** `@Bean` 都带 `@ConditionalOnMissingBean`，
所以你不需要开关、不需要排除自动配置、更不需要 fork。

> 例外只有三个**基础设施** bean，它们带 `static`（必须早于普通 bean 就绪）、刻意不参与替换：
> `llmToolCatalog`（`@LlmTool` 扫描的 `BeanPostProcessor`）、`llmComponentRegistrar`
> （`@LlmLoop` / `@LlmGuard` 的注册器）、`llmBuiltinToolRegistrar`
> （只在 `llm.tools.builtin-enabled=true` 时存在）。
> 事件监听（`AgentListener`）是**叠加**语义：注册一个同类型 bean 会让内置的
> `llmLoggingListener` 让位（按类型判断），但多个自定义监听器之间会全部生效。

---

## 快速开始

### 1. 依赖

```xml
<dependency>
    <groupId>com.benxin</groupId>
    <artifactId>benxin-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

要求 Java 17+ / Spring Boot 3.x。核心模块 `benxin-core` **不依赖 Spring**，可以单独使用。

### 2. 配置一个模型

```yaml
llm:
  default-model: deepseek
  models:
    deepseek:
      protocol: openai
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_KEY}
      model: deepseek-chat        # 下发给上游的模型 id（不是上面那个 key）
```

`llm.models.<key>.*` 的完整字段与默认值：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `protocol` | `openai` | 三选一：`openai` / `anthropic` / `gemini` |
| `base-url` | — | 厂商或网关地址（末尾 `/` 会被去掉） |
| `api-key` | — | 鉴权用；也会作为各协议默认头的一部分 |
| `model` | — | **下发给上游的模型 id**。与 `llm.models` 的 key（注册名）是两件事：`@LlmAgent(model = "deepseek")` 用的是 **key**，报文里发出去的是这一行 |
| `max-retries` | **2** | 瞬时错误（429/5xx/网络中断）的指数退避重试次数。注意默认值不是 0 —— 想数报文或工具调用次数时请显式写 `max-retries: 0`，否则"一次调用 = 一笔请求"不成立 |
| `temperature` / `max-tokens` | 不发送 | 请求里没显式设置时才用配置值 |
| `connect-timeout` / `read-timeout` / `stream-read-timeout` | 15s / 5min / 10min | — |
| `headers` / `extra` | 空 | `extra` 里的键会合并进请求体（配置级作为底，请求级覆盖之） |
| `primary` / `order` | false / 0 | 未指定 `llm.default-model` 时，用来选出默认模型 |

### 3. 写一个工具

```java
@Component
public class WeatherTools {

    @LlmTool(name = "get_weather", description = "查询城市天气")
    public Weather get(@LlmToolParam(value = "city", description = "城市名") String city) {
        return weatherService.query(city);   // 返回值自动序列化成工具结果
    }
}
```

### 4. 声明一个 Agent

```java
@LlmAgent(model = "deepseek", loop = "dsh-minimal", tools = {WeatherTools.class})
public interface WeatherAssistant {
    String ask(String question);
}
```

### 5. 注入使用

```java
@Service
public class MyService {
    private final WeatherAssistant assistant;   // 构造注入

    public String ask(String q) {
        return assistant.ask(q);                // 一次普通的 Java 调用
    }
}
```

就这些。**没有 `LlmClient`、没有 `ChatRequest`、没有手写 JSON。**

---

## 注解速查

| 注解 | 位置 | 作用 |
|---|---|---|
| `@LlmAgent` | 接口 | 把接口变成可注入的 Agent；方法调用 = 一次 Agent 运行 |
| `@LlmLoop("name")` | 类 | 注册一个自定义 Agent 循环（**无需再补 `@Component`**，主包下会被自动发现）；`defaultLoop = true` 时在没有显式配置 `llm.agent.default-loop` 的情况下成为默认 Loop，多个候选按 `order()` 取最小者 |
| `@LlmModelDef("name")` | 类 | 用指定名字注册一个 `LlmModel` bean |
| `@LlmTool` | 方法/类 | 把方法暴露给模型调用；`requiresApproval = true` 要求人工审批，`parallelSafe = false` 要求串行执行（类与方法都标注时取**安全侧**） |
| `@LlmToolParam` | 参数 | 描述工具参数的名称/说明/是否必填/默认值。**`required = true` 且无默认值时运行期会真的校验**：模型漏参会被回灌"缺少必填参数 [x]"；`Optional<T>` 参数一律视为可缺省 |
| `@SystemPrompt` | 方法 | 方法级系统提示词，支持 `{变量}` 插值（可引用 `@User` 参数与 `@Ctx` 变量） |
| `@User` / `@Assistant` | 参数 | 指定参数作为 user / assistant 消息（**无注解参数默认即 user**） |
| `@Ctx("name")` | 参数 | 上下文变量：只参与提示词渲染与运行属性，不进消息体 |
| `@Memory` | 参数 | 参数值作为 sessionId，自动装载/保存会话历史 |
| `@LlmGuard` | 类 | 声明式注册拦截器，可限定只对某些 Agent 生效（同样无需 `@Component`） |
| `@LlmRetry` | 方法/类 | 声明重试策略：**工具方法上**=重试该工具的执行；**Agent 接口（或其方法）上**=重试模型调用，`includeTools = true` 时把该 Agent 的全部工具也包上重试 |
| `@EnableLlmAgents` | 配置类 | **必写**：开启 `@LlmAgent` 接口扫描；不写 `basePackages` 时从主应用包推断 |

> ⚠️ `@EnableLlmAgents` 是**必须**的。starter 里没有任何自动配置会帮忙扫描 `@LlmAgent` 接口 ——
> "不写则从主应用包推断"说的是**扫描范围**，而不是"注解可以不写"。
> 完全不写时，一个接口都不会被注册，注入会直接抛 `NoSuchBeanDefinitionException`。
> 另外它现在遵守 `llm.enabled=false`：总开关关掉时不会再往容器里留一批
> "一用就炸"的代理 bean（那类失败会被推迟到第一次调用，最难排查）。

### 声明式装配的分工

`@LlmAgent` 只描述意图，真正生效的是容器里的 bean：

| 注解属性 | 落点 |
|---|---|
| `name` / `systemPrompt` / `maxSteps` / `temperature` / `maxTokens` / `memory` | `AgentSpec`，空缺项回落到 `llm.agent.*` |
| `model` | **模型注册表的 key**（`llm.models.<key>` 或某个 `LlmModel` bean 的名字），不是报文里的模型 id —— 后者由 `llm.models.<key>.model` 决定 |
| `loop` | `LoopRegistry` 里的名字；未注册时打 WARN 并回退默认 Loop |
| `tools` / `toolNames` | 显式列出的工具；**两者都留空时继承容器的全局工具表** |
| `subAgents` | 惰性子代理壳，避免 A ⇄ B 的构建环 |
| `interceptors` | 追加的拦截器实现类，按 `order()` 升序并入全局拦截器链（优先取容器 bean，取不到则直接实例化） |

`llm.agent.hard-max-steps` 是防呆硬上限，夹取逻辑落在 `AgentBuilder.build()` 这一层。
声明式（`@LlmAgent`）由 `LlmAgentFactory` 自动传入该上限；
**程序化 `Agent.builder()` 需要自己调 `.hardMaxSteps(n)`** —— 代码里的 `maxSteps` 被视为显式意图，
不会被配置静默改小。`maxSteps(0)` 现在会**直接报错**（以前被静默忽略、回落到 24）。

### 参数与返回值

```java
@LlmAgent(loop = "claude-code")
public interface CodeReviewer {

    // 返回强类型对象：模型输出的 JSON 会被自动反序列化
    @SystemPrompt("以 JSON 输出 {summary, issues[], score}")
    ReviewReport review(@User String code);

    // @Ctx 参与提示词渲染；@Memory 让多轮对话自动续接
    @SystemPrompt("以 {style} 的风格解释给 {audience} 听")
    String explain(@User String code, @Ctx("style") String style,
                   @Ctx("audience") String audience, @Memory String sessionId);

    // 流式：多一个 LlmStreamHandler 参数即可，不需要另一套 API
    String stream(@User String code, LlmStreamHandler handler);

    record ReviewReport(String summary, List<String> issues, int score) {}
}
```

支持的返回类型：`String` / `AgentResult`（含步数、用量、工具调用记录）/ `void` /
`Optional<String>` / `List<ChatMessage>` / `int`（步数）/ `long`（耗时）/ `boolean` /
**任意 DTO（按 JSON 反序列化）**。

> `Optional<String>` 在"模型什么都没说"（输出为空白）时返回 `Optional.empty()`，
> 因此 `maybe(...).orElse("兜底")` 能拿到兜底值。

---

## 四种主流 LLM API 协议

"协议"与"厂商"是**正交**的。同一套 OpenAI Chat Completions 协议被大量服务兼容，
所以换厂商往往只需要改两个字段。

### 一、OpenAI Chat Completions

`POST {base-url}/v1/chat/completions`

覆盖：OpenAI、DeepSeek、通义千问（兼容模式）、Kimi、智谱 GLM、vLLM、Ollama、OneAPI、LiteLLM……

```yaml
llm:
  models:
    deepseek:
      protocol: openai
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_KEY}
      model: deepseek-chat
    qwen:
      protocol: openai
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      api-key: ${DASHSCOPE_KEY}
      model: qwen-max
    ollama:                              # 本地模型，无需 key
      protocol: openai
      base-url: http://localhost:11434
      api-key: ollama
      model: qwen2.5:14b
```

处理了：`tools[].function`、并行 `tool_calls`、SSE `data:` 流与 `[DONE]`、
**流式工具参数的按 index 分片拼装**、DeepSeek 的 `reasoning_content`、
`stream_options.include_usage`。

### 二、Anthropic Messages

`POST {base-url}/v1/messages`

```yaml
llm:
  models:
    claude:
      protocol: anthropic
      base-url: https://api.anthropic.com
      api-key: ${ANTHROPIC_KEY}
      model: claude-sonnet-4-5
      max-tokens: 8192                  # Anthropic 必填，框架会自动兜底
```

处理了：顶层 `system`、content block 数组、`tool_use` / `tool_result` block、
**连续工具结果合并进同一个 user 回合**（Anthropic 的硬性要求）、
`input_json_delta` 增量拼装、`thinking` block 与 `signature` 往返、
`anthropic-version` 头、`cache_read_input_tokens`。

### 三、Google Gemini

`POST {base-url}/v1beta/models/{model}:generateContent`（流式加 `?alt=sse`）

```yaml
llm:
  models:
    gemini:
      protocol: gemini
      base-url: https://generativelanguage.googleapis.com
      api-key: ${GEMINI_KEY}
      model: gemini-2.5-pro
```

处理了：`contents[].parts`、`systemInstruction`、`functionCall` / `functionResponse`、
**无 tool id 情况下的 id 合成与结果对位**（合成 id 形如 `call_<工具名>_<序号>`，
因此只带 id 回灌的结果也能对回正确的工具）、`generationConfig`、
**JSON Schema 清洗**（剔除 Gemini 不接受的 `additionalProperties` 等字段）、
`thought` 标记与 `usageMetadata`。

两个容易被忽略的口径：

* **思考内容仅解析、不回传。** 响应里 `thought: true` 的文本会被归一成 `ThinkingPart`；
  但回灌历史时 Gemini 不接受把思考作为输入，因此编码阶段会丢弃 `ThinkingPart`。
* **`usageMetadata` 是累计口径。** 官方逐帧发送"截至当前的累计值"，本心的流式解码器会把它
  换算成增量再向上层回调，所以多帧之间的用量是**相加**得到的真实值，而不是被重复计入。

### 四、OpenAI Responses API

`POST {base-url}/v1/responses`

```yaml
llm:
  models:
    gpt-responses:
      protocol: responses
      base-url: https://api.openai.com
      api-key: ${OPENAI_KEY}
      model: gpt-4o
    deepseek-responses:                  # DeepSeek 也提供兼容端点
      protocol: responses
      base-url: https://api.deepseek.com/responses
      model: deepseek-flash
```

**它与 Chat Completions 是两套不同的报文结构**，不是同一个 API 的开关，所以在本心里是并列的两个协议：

| | Chat Completions | Responses API |
|---|---|---|
| 系统提示 | messages 里的 `system` 角色 | **顶层 `instructions`** |
| 对话载体 | `messages[]` | **`input[]` item 列表** |
| 工具声明 | `tools[].function.{name,parameters}` | **`tools[].{name,parameters}`（扁平）** |
| 工具调用 | assistant 消息里的 `tool_calls[]` | **独立的 `function_call` item** |
| 工具结果 | `role: "tool"` 消息 | **独立的 `function_call_output` item** |
| 输出上限 | `max_tokens` | **`max_output_tokens`** |
| 结束原因 | `finish_reason` | **`status`** |
| 流式 | 匿名 SSE + `[DONE]` | **命名事件，且没有 `[DONE]`** |

处理了：顶层 `instructions`、`input` item 摊平与还原（`message` / `function_call` /
`function_call_output`，含 `input_image` 多模态）、扁平 `tools[]`、`tool_choice` 四态、
`status` → 统一结束原因、`usage.input_tokens_details.cached_tokens` 与
`output_tokens_details.reasoning_tokens`，以及命名 SSE 事件流
（`response.output_text.delta` / `response.reasoning_text.delta` /
`response.function_call_arguments.delta` / `response.output_item.done` /
`response.completed` · `incomplete` · `failed`）。

三个容易踩错的口径：

* **没有 `[DONE]`。** 流的结束靠 `response.completed` / `response.incomplete` /
  `response.failed` 三个终态事件；网关提前断流时 `finish()` 会把已累积的工具调用交出去，
  不会静默吞掉。
* **工具参数按 `output_index` 分片。** 与 Chat Completions 的 `index` 语义相同，
  但完整信息在 `response.output_item.done` 里，本心以它为准。
* **`incomplete` 归为"被长度截断"。** 目前唯一的 incomplete 原因就是触达
  `max_output_tokens`，上层据此判断输出只是半成品。
* **思考内容仅解析、不回传。** 与 Gemini 侧同一个取舍：`reasoning` item 需要配套的
  加密内容结构，而 `ThinkingPart` 只保存纯文本，硬塞回去多数端点会 400。

**失败的工具结果怎么表达（四个协议的统一口径）：**

| 协议 | 失败标记 |
|---|---|
| OpenAI Chat Completions | `content: "[error] " + 内容`（前缀标记） |
| Anthropic | `is_error: true`（结构化字段） |
| Gemini | `response.error` 与 `response.result` 二分 |
| Responses | `output: "[error] " + 内容`（前缀标记 —— `function_call_output` 没有结构化的错误字段） |

这条信息很重要：模型看到 `"工具炸了"` 时若无从判断这是工具正常返回的文本还是工具失败了，
它会把错误当成合法结果继续往下走，而不是改方案或重试。四个协议都把它转成了可辨识的形式。

### 加自己的协议

```java
@Component
public class MyBedrockCodec implements ProtocolCodec {
    @Override public Protocol protocol() { return Protocol.of("bedrock"); }   // 任意名字都行
    // endpoint / headers / encode / decode / newStreamDecoder
    // 可选：capabilities() 声明该协议支持流式/工具调用/上下文窗口
}
```

注册成 bean 即可，然后在配置里写 `protocol: bedrock` —— **调用方代码一行都不用改**：

```yaml
llm:
  models:
    bedrock:
      protocol: bedrock
      base-url: https://bedrock.internal
      model: anthropic.claude-v2
```

这条链路之所以成立，是因为三处都做了改动：`Protocol` 是可扩展的标识类型（不再是封闭枚举）、
starter 会把容器里所有 `ProtocolCodec` bean 收进 `ProtocolRegistry`、`ModelFactory`
按注册表解析协议而不是走写死的分支。

几个有用的细节：

* **同名即覆盖，但不再静默。** 用户 bean 在内置之后登记，所以 `protocol()` 返回
  `Protocol.OPENAI` 就能用自己的实现顶掉内置的 `openai` —— 这是刻意保留的能力，
  但注册时会打一条 WARN，因为它换掉的是**全应用的默认通道**。
* **`protocol()` 不能返回空名字。** `Protocol.of(...)` 对 `null` / 空串 / 纯空白直接抛
  `IllegalArgumentException`（消息里点名是哪个 Codec 干的），启动期就失败。
  "缺省即 `openai`" 是配置项 `llm.models.<key>.protocol` 的默认值，由配置绑定层兜底 ——
  标识类型不替调用方猜默认值，否则"我没填名字"与"我就是 openai"在类型层面无法区分，
  一个半成品的 Codec 会把 OpenAI 通道悄悄换掉。
* **能力声明归 Codec。** `capabilities()` 有默认值，自定义协议可以不写；但如果你的协议
  不支持流式或工具调用，**务必覆写它** —— Loop 会据此改变交互方式。
* **未注册的协议仍然快速失败。** 配置里写了一个没有 Codec 的协议名，应用会在启动期
  报错并列出所有已注册协议，不会拖到第一次请求才炸：
  `模型 [x] 使用了未知协议 [bedrock]，已注册的协议: [openai, anthropic, gemini, responses]。…`
* **不用 Spring 也能注册。** 裸 core 场景用
  `ModelFactory.create(config, transport, ProtocolRegistry.withBuiltins().register(codec))`，
  或直接 `Benxin.withCodec(config, codec, transport)`。

---

## 内置 Agent Loop

| 名字 | 设计来源 | 特征 |
|---|---|---|
| `dsh-minimal` | DSH 极简 | `调模型 → 有工具就执行 → 再调模型`，约 80 行。零内置工具、零压缩，**故意什么都不多做**，是"一切可插拔"的最小样板 |
| `react` | 经典 ReAct | Thought → Action → Observation；**模型不支持 function calling 时自动降级到文本协议**（解析 `Action:` / `Action Input:` / `Final Answer:`） |
| `claude-code` | Claude Code | 单一主循环 + **子代理派生**（独立上下文、只回传结论）+ 待办管理 + **自动上下文压缩** + **连续工具错误提醒**（注入一条"停下来重新评估"的提醒让模型自己改，**不强制停机**） |
| `codex` | OpenAI Codex | **turn 制**：`update_plan` 出计划 → `apply_patch` 做最小改动 → 命令验证；**如实告知沙箱权限边界**；未验证则给一次补验证的机会 |
| `plan-execute` | 经典范式 | 第一步强制产出编号计划，之后逐步推进并回显进度 |
| `reflexion` | 经典范式 | 执行 → 自省评分 → 低于阈值则带着批评重做 |
| `workflow` | 声明式编排 | **下一步由图决定，而不是模型**：节点与边写在 YAML/JSON 里，分支条件是表达式。可审计、可复现、成本可控 |
| `staged` | 结构化编排 | 规划 → 执行 → **校验** → 修复 → 汇总；汇总前强制验收，不通过则带问题回到修复阶段 |

### 选一个

```yaml
llm:
  agent:
    default-loop: claude-code
```

```java
@LlmAgent(loop = "codex")          // 或逐个指定
public interface MyAgent { ... }
```

### 写自己的

```java
@LlmLoop("my-loop")
public class MyLoop extends AbstractAgentLoop {

    @Override public String name() { return "my-loop"; }

    @Override protected LoopResult doRun(LoopContext ctx) {
        // 模型调用、工具执行、沙箱校验、审批、流式聚合、用量统计
        // 全都由 ctx 处理好了，这里只写"思考的步骤"
        ctx.append(ChatMessage.user("先复述任务，再动手。"));
        ctx.callModel();
        return runToolCallingLoop(ctx);      // 复用标准骨架
    }
}
```

`LoopContext` 提供：`callModel()` / `executeTools()` / `callTool()` / `append()` /
`spawnSubAgent()` / `emit()` / `messages()` / `attributes()` / `outOfBudget()`。

自定 Loop 还可以实现 `SubAgentObserver`：子代理的真实派生路径
（`task` 工具 → `ToolContext.spawn` → `DefaultLoopContext.spawn`）会回调它，
因此"补记子代理用量、发出自己的子代理事件"不再需要另写一个没人调用的入口方法
（用量补记本身由 `DefaultLoopContext` 统一做掉，所有 Loop 一起受益）。

---

## 工作流模式

其它 Loop 里，"下一步做什么"由模型当场决定。工作流模式把这件事反过来：
**模型只负责填内容，控制流由我们决定**。本心给了两条路线，按"流程定不定得下来"选。

### 一、声明式：`workflow` —— 图写在 YAML 里

```yaml
# llm.workflows.release-notes.location=classpath:workflows/release-notes.yml
name: release-notes
nodes:
  - id: begin
    type: start
    prompt: "请为这次变更写发布说明：\n${input}"

  - id: classify            # agent 节点：一次完整的子任务（内部仍是完整工具循环）
    type: agent
    instruction: 你是发布经理，只关注用户能感知到的变化。
    prompt: "归纳要点：\n${begin}"

  - id: route
    type: branch            # 纯路由：自己不产生输出，只按出边条件挑一条

  - id: urgent
    type: agent
    prompt: "补充回滚方案与影响范围：\n${classify}"

  - id: stamp
    type: tool              # 不经过模型，直接调工具
    tool: get_current_time

  # 两条分支各写各的变量：把 set 放在分支之后，"走了哪条路"才是可读的
  - id: stamp-urgent
    type: set
    set:
      channel: 加急通道

  - id: stamp-normal
    type: set
    set:
      channel: 常规通道

  - id: report
    type: end               # 渲染 output 作为最终答案
    output: "通道：${channel}\n时间：${stamp}\n\n${classify}"

edges:
  - from: begin
    to: classify
  - from: classify
    to: route
  # 出边按声明顺序取第一条成立的 —— 顺序即优先级，默认边放最后
  - from: route
    to: urgent
    when: "${input} contains 紧急"
  - from: route
    to: stamp-normal
  - from: urgent
    to: stamp-urgent
  - from: stamp-urgent
    to: stamp
  - from: stamp-normal
    to: stamp
  - from: stamp
    to: report
```

> 注意上面这处**分支与变量**的写法：两条分支各自 `set` 自己的 `channel`，然后汇聚到 `stamp`。
> 如果把 `channel` 写在两条分支**共同的下游**（例如单独一个 `mark` 节点）上，
> 那么无论走哪条路 `${channel}` 都会被写成同一个值 —— 它就不能像注释暗示的那样
> "记下走了哪条路"了。加载期会对"默认边之前还有条件边"（那之后的边永远不可达）打警告，
> 但"变量写在了汇聚点上"这种语义错误只能靠这条经验法则避免。

用它不需要写任何 Java —— 声明一眼就能引用：

```java
@LlmAgent(model = "mock", loop = "workflow:release-notes", tools = {DevTools.class})
public interface ReleaseNoteWriter {
    String draft(String changeDescription);
}
```

**六种节点**：`start`（透传输入）、`agent`（一次模型子任务）、`tool`（直调工具）、
`branch`（按条件路由）、`set`（写变量）、`end`（产出最终答案）。
**循环不需要专门的节点**：把边指回上游即可。

**条件表达式**是一小门贴近自然语言的语言，支持 `==` `!=` `>` `>=` `<` `<=`
`contains` `matches`（正则）`is empty` `exists` `missing`，以及
`not` / `and` / `or` 与括号（也接受 `!` `&&` `||` 与 `非` / `且` / `或`）：

```
${verdict} == 通过
${analyze} contains 严重 and ${count} > 10
${log} matches (?i)error|异常
${summary} is not empty
```

> `exists` 的语义是 **"≠ null"**，不是"非空"：变量被 `set` 成空串时
> `"" exists` 为 `true`，同时 `"" is empty` 也为 `true`。想判断"有内容"请用 `is not empty`。

**模板**用 `${名称}` 取值：`${input}` 是任务原文，`${last}` 是上一个输出，
`${error}` 是上次失败原因，其余是节点 id 或 `set` 写入的变量。
`${名称:-兜底值}` 提供默认值；**取不到的占位符保留原文**，让拼写错误一眼可见，
而不是静默变成空串。

**节点级容错**：`retry: N` 与 `continueOnError: true` 对 `agent` 节点与 `tool` 节点一视同仁 ——
工具不存在、被沙箱拒绝、工具抛异常、超时都会算作"该节点失败"，从而触发重试或补偿分支
（`tool` 节点不再把一句"未知工具 […]"当成正常输出悄悄流下去）。

**轨迹口径**：**硬失败**的节点也会进访问表（`workflow.node` 事件在 catch 块里补发一次），
因此 `WorkflowRun.visited()` 与访问表证明的是"**尝试过**哪些节点"，而不只是"成功走过哪些节点"。
被**死循环护栏**拦下的那一次不进访问表（它没有执行），护栏事件里的 `visits`
也按"实际执行过的次数"上报，与实际落库数**口径一致**。
`executions()` 数的是访问次数，**不含重试**；重试另记在 `workflow.node-retry` 事件里。
预算耗尽时 `AgentResult.maxStepsReached()` 为 `true`（与其它 Loop 一致），
`WorkflowRun.truncated()` 仍是最直接的判据。

**失败文案只有一份**：同一个失败会出现在四个可观测面 —— `workflow.node-error` 事件负载、
访问表的 `error` 列、`${error}` 模板变量、以及出边的条件表达式。它们都用
`节点 [<id>]（<类型>）执行失败：<原因>` 这一份文案，排查时看哪一处都得出同样的结论
（原始异常消息是它的子串，按原文匹配不会失效）。

**三道防线**：步数预算拦住"模型一直调工具"；单节点访问上限
（`maxVisitsPerNode`，默认 100）拦住"不调模型的死循环"（预算对后者完全无效）；
节点级 `retry` 与 `continueOnError` 把"偶发失败"和"整张图崩掉"分开 ——
后者还能让出边读 `${error}` 走补偿分支。

定义也可以在调用时逐次传入，同一个 Loop 跑不同的图：

```java
agent.call(input, sessionId, Map.of("benxin.workflow", yamlOrJsonText), stream);
```

### 二、结构化编排：`staged` —— 流程固化在代码里

```
PLAN ──► EXECUTE ──► VERIFY ──通过──► SYNTHESIZE ──► 结束
                       │  ▲
                     不通过 │
                       ▼  │
                     REPAIR ┘（最多 maxRepairRounds 轮）
```

与 `plan-execute` 只差一件事，但很关键：**它多了一道验收闸门**。
plan-execute 在最后一步走完就直接汇总，于是"步骤都跑了"和"任务真的完成了"被当成一回事 ——
而这两件事经常不是一回事。`staged` 在汇总前让模型换个身份独立复查一次，
不通过就带着具体问题回到修复阶段。

```java
@LlmAgent(model = "deepseek", loop = "staged", maxSteps = 16)
public interface Analyst {
    String analyze(String topic);
}
```

阶段与结论都会留档，便于展示与复盘：

```java
result.attributes().get("benxin.staged.plan");        // 计划步骤
result.attributes().get("benxin.staged.verdict");     // pass / fail
result.attributes().get("benxin.staged.stage");       // 最终阶段
result.attributes().get("benxin.staged.repair-rounds");
```

自定义强度：`new StagedLoop(verifyEnabled, maxRepairRounds)` ——
关掉验收就退化成"带汇总的 plan-execute"。

### 什么时候用哪条

| | 声明式 `workflow` | 结构化编排 `staged` |
|---|---|---|
| 流程 | 事先画得出来 | 任务形态多变 |
| 控制流 | 图（可审计、可复现） | 代码里的阶段机 |
| 改流程 | 改 YAML，不动 Java | 改代码 |
| 适合 | 审批、发布、评测等固定流程 | 分析、调研等开放式任务 |

两者都不适合探索性任务 —— 那种场景应该用 `react`。

---

## 工具

### 注解方式

```java
@Component
public class DevTools {

    @LlmTool(name = "read_file", description = "读取文件的指定行范围")
    public String read(@LlmToolParam(value = "path", description = "相对路径") String path,
                       @LlmToolParam(value = "limit", description = "行数", required = false,
                                     defaultValue = "200") int limit) {
        // ...
    }
}
```

参数支持基本类型、枚举、集合、`Optional`、自定义 POJO（自动生成嵌套 JSON Schema 并绑定）。
返回 `String` / DTO（自动序列化）/ `ToolResult`（需要自己控制错误标记时）。

三件以前只能靠"手写 `ToolCallback`"才能表达的事，现在注解上就有：

```java
@LlmTool(name = "create_order", description = "下单", requiresApproval = true, parallelSafe = false)
@LlmRetry(maxAttempts = 3, backoffMillis = 200)      // 工具执行失败时按退避重试
public String create(@LlmToolParam("sku") String sku,
                     @LlmToolParam(value = "note", required = false) Optional<String> note,
                     ToolContext ctx) { ... }
```

| 写法 | 语义 |
|---|---|
| `requiresApproval = true` | 与手写 `ToolCallback#requiresApproval()` 等价：`ApprovalPolicy.ON_REQUEST`（默认）下会先问审批 |
| `parallelSafe = false` | 同一轮里多个工具调用串行执行（有副作用、依赖共享状态的工具必须这么写） |
| `@LlmRetry` | 只重试"失败结果"（`ToolResult.error`）；参数类确定性错误不重试；重试次数耗尽后返回最后一次结果 |
| `AgentSession session` 参数 | 由框架注入**真实会话句柄**（`id()` / `history()` / `chat()` / `attributes()`），不再像 1.1.0 那样恒为 `null` |
| `ToolContext ctx` 参数 | 由框架注入执行上下文（`sessionId()` / `attributes()` / `sandbox()` / `spawn(...)`） |

`defaultValue` 生成的 schema `default` 会按参数类型转换（`integer` → 数字、`boolean` → 布尔、`array`/`object` → 解析 JSON），
不再恒为字符串；POJO 展开深度超限时也只截断"对象展开"，标量字段保留自己的类型。

### 程序化方式

```java
@Bean
public ToolCallback searchTool(SearchService service) {
    return new ToolCallback() {
        @Override public ToolSpec spec() {
            return new ToolSpec("search", "搜索知识库", schema);
        }
        @Override public ToolResult call(Map<String, Object> args, ToolContext ctx) {
            return ToolResult.ok(service.search((String) args.get("q")));
        }
        @Override public boolean requiresApproval() { return false; }
    };
}
```

### 内置工具（默认全部关闭）

| 工具 | 作用 | 需审批 |
|---|---|---|
| `read` | 按行号范围读文件 | 否 |
| `write` | 写文件（自动建父目录） | **是** |
| `edit` | 精确字符串替换（多处匹配时拒绝，避免误改） | **是** |
| `glob` | 按 glob 找文件，按修改时间排序 | 否 |
| `grep` | 正则搜索文件内容 | 否 |
| `bash` | 执行命令（合并 stdout/stderr，带超时与截断） | **是** |
| `todo_write` | 维护待办清单，长任务保持聚焦 | 否 |
| `task` | 派生子代理执行独立子任务，只回传摘要 | 否 |

**安全默认值**——三重开关，缺一不可：

```yaml
llm:
  tools:
    builtin-enabled: false      # ① 总开关，默认关
    workdir: .                  # 沙箱围栏根目录，所有路径必须落在其内
    allow-write: false          # ② 写文件，默认关
    allow-exec: false           # ③ 执行命令，默认关
    allowed-commands: []        # 非空则命令必须以此开头（白名单，替换语义）
    denied-commands: []         # 命中即拒绝（黑名单，**追加**在内置那批危险命令之上）
    exec-timeout: 60s
    max-output-chars: 30000
    enabled: []                 # 精确点名：**增量**叠加在只读基线上，不是白名单
```

三点值得单独说明：

* **`enabled` 是增量，不是白名单。** 只读三件套（`read` / `glob` / `grep`）永远是基线，
  `enabled: [read]` 的最终结果是 `read + glob + grep`，不是"只有 read"。
  另外 `todo_write` 与 `task` **不在**默认基线里 —— 想要它们必须显式点名。
  八个全开 = `builtin-enabled: true` + `allow-write: true` + `allow-exec: true`
  + `enabled: [todo_write, task]`。
* **`enabled` 里点名写/执行类工具等价于同时授权。** 写 `enabled: [write]` 就会得到
  `allow-write: true` 的效果，不会出现"工具看得见却每次都被沙箱拒绝"。
* **`denied-commands` 是追加，不是替换。** 你写的条目叠加在内置的 29 条高危命令之上
  （`rm -rf /`、`mkfs`、fork bomb、`dd if=`、`shutdown` …），因此"只多加一条 docker"
  不会让内置那批失去保护。需要整体替换时用代码里的 `SandboxConfig.Builder#deniedCommands(...)`。

即便全部打开，`PathSandbox` 仍会拦住目录穿越、`.git/`、`.env`、`*.pem`、`id_rsa` 等敏感路径，
`CommandSandbox` 仍会拦住 `rm -rf /`、`mkfs`、fork bomb 等模式。

**沙箱与内置工具开关无关。** `llm.tools.builtin-enabled=false` 只是"不装配内置工具"，
容器里的 `ToolSandbox` 依然是真正的 `PathSandbox + CommandSandbox` ——
**你自己写的 `@LlmTool` 同样受围栏约束**（以前这里会退化成 `ToolSandbox.permissive()`，
等于默认配置下根本没有沙箱）。

**沙箱拒绝是硬约束，不可被审批覆盖。** `ApprovalPolicy.ON_FAILURE` 的语义是
"调用失败之后再问一次"，而沙箱拒绝表达的是使用者在配置里写死的边界，因此不会被
审批 handler 推翻。另外注意 `NEVER` 是 **"不问、直接放行"**（最宽松的一档），
不是"不问就拒绝"；想一律拒绝请用 `ApprovalHandler.denyAll()`。

---

## 替换任意一个切面

### 换模型（代码方式，绕过 yml）

```java
@Bean
public LlmModel myModel(HttpTransport transport) {
    return new OpenAiModel(ModelConfig.builder("internal")
            .baseUrl("http://llm-gateway.internal/v1")
            .apiKey("...")
            .model("internal-72b")
            .build(), transport);
}
```

### 给模型加装饰器（缓存 / 限流 / 录制回放）

```java
public class CachedModel implements LlmModel {
    private final LlmModel delegate;
    private final Cache cache;

    @Override public String name() { return delegate.name(); }
    @Override public ChatResponse chat(ChatRequest req) {
        return cache.get(req, () -> delegate.chat(req));
    }
    @Override public void stream(ChatRequest req, LlmStreamHandler h) {
        delegate.stream(req, h);
    }
    @Override public LlmModel unwrap() { return delegate; }   // 便于链式取回
}
```

内置的 `RetryingLlmModel` 就是这种写法的示范（瞬时错误指数退避；流式下仅在尚未吐出任何内容时重试）。

### 换记忆（Redis）

```java
@Bean
public MemoryStore memoryStore(RedisTemplate<String, String> redis) {
    return new MemoryStore() {
        @Override public List<ChatMessage> load(String id) { /* ... */ }
        @Override public void save(String id, List<ChatMessage> h) { /* ... */ }
        @Override public void clear(String id) { /* ... */ }
    };
}
```

### 换上下文压缩策略

```java
@Bean
public ContextCompactor compactor(TokenEstimator estimator) {
    return new SlidingWindowCompactor(estimator, 0.75, 2048);   // 不调用模型，成本可预测
}
```

### 换提示词来源（接提示词管理平台）

```java
@Bean
public SystemPromptProvider promptProvider(PromptRegistry registry) {
    return (spec, vars) -> registry.fetch(spec.name(), vars);
}
```

### 加拦截器

```java
@Component
@LlmGuard(agents = {"codeReviewer"})     // 去掉 agents 即全局生效
public class AuditInterceptor implements AgentInterceptor {

    @Override public int order() { return 100; }

    @Override public ChatRequest beforeModel(AgentInvocation inv, ChatRequest req) {
        return req.toBuilder().extra("trace_id", inv.sessionId()).build();
    }

    @Override public ToolResult beforeTool(ToolInvocation inv) {
        return null;                      // null = 放行；返回结果则短路
    }
}
```

---

## 可观测性

### 事件监听

```java
@Bean
public AgentListener metrics(MeterRegistry registry) {
    return new AgentListener() {
        @Override public void onTextDelta(String d) { /* 推到前端 */ }
        @Override public void onToolResult(ToolInvocation inv, ToolResult r) { /* 打点 */ }
        @Override public void onAgentEnd(AgentResult r) { /* 记录步数/用量/耗时 */ }
    };
}
```

### 运行结果

```java
AgentResult r = assistant.call("...");
r.text();          // 最终答案
r.steps();         // 走了几步
r.usage();         // token 用量（含缓存与推理 token）
r.toolCalls();     // 完整工具调用记录（名称/参数/结果/耗时）
r.maxStepsReached(); // 是否因步数上限中断
r.attributes();    // 循环写入的统计信息
```

### 几条容易猜错的行为口径

| 行为 | 实际口径 |
|---|---|
| 模型声明 `capabilities().streaming == false` | `LoopContext.callModel` 改走 **`chat()` 同步分支**（因此自定义传输层只实现 `post()` + `decode()` 也能在 Agent 路径上跑通）；支持流式的仍走 `stream()` |
| 自定义传输层在 `postStreaming()` 里返回非流式响应 | 得到一条可读的 `ModelException`（提示必须返回 `TransportResponse.streaming(...)`），而不是 `SseParser` 里的 NPE |
| 网关把 SSE 缓冲成整包（`200` + `application/json`） | 1.1.1 起同样得到可读的 `ModelException`。此前 `postStreaming` 只看状态码，整包被当成事件流，解析出 0 个事件 → 上层拿到**静默空回答** |
| 流式重试 | `onStart` 在一次 `stream()` 调用内**恰好一次**，重试不会让订阅方被重新初始化；已经吐出内容后不再重试 |
| 一次失败的运行 | `AgentListener#onError` **只广播一次**（1.1.0 会广播两次：Loop 与 Agent 各一次，按异常计数/告警的监听器会翻倍） |
| 工具调用五道关卡的顺序 | **沙箱 → 审批 → 拦截器 beforeTool → 执行 → 截断/afterTool**。因此拦截器只看得见"通过了沙箱"的调用（要审计全部调用请用 `AgentListener`） |
| `beforeTool` 返回非 null 短路 | 只是跳过**执行**：链上其余拦截器仍会收到 `beforeTool` 与 `afterTool`（第一个非 null 的结果生效） |
| 审批被拒时的文本 | `用户拒绝执行工具 [名字]：<理由>`；覆写 `ApprovalHandler#decide(...)` 就能把理由告诉模型 |
| `ApprovalPolicy.ON_FAILURE` | 先执行；**执行失败**后再问一次，批准则重试一次。沙箱拒绝是硬边界，任何策略都覆盖不了 |
| `AgentRegistry.resolved()` | 只含**已解析**的 Agent（惰性条目要 first-call 才进来）；全量请用 `names()` + `find()`，或新加的 `resolveAll()` |
| 运行属性里的 `benxin.sessionId` / `benxin.agentName` | 1.1.1 起由 `DefaultAgent.run` 在运行开始时播种（此前这两个常量按名取值永远是 null） |
| `agent.toBuilder()` | 携带监听器；`.model("名字")` / `.loop("名字")` 与实例形式之间是**后设者胜**（1.1.0 是实例恒胜，名字被静默忽略） |
| 子代理用量 | 补记进父上下文（`AgentResult.usage()` 含子代理消耗），并广播平台级 `subagent_start/end`；claude-code 另有 `claude-code.subagent-start/end` |
| claude-code 兜底系统提示词 | 幂等：历史里已有同一段提示时不再重复注入（开记忆也不会线性累积） |
| `ModelRegistry.defaultName()` / `defaultModel()` | 两者口径一致：配置的默认名取不到时都回退到**按注册顺序的第一个**模型（`LinkedHashMap`，结果稳定）；想拿"配置里原样写的名字"用 `configuredDefaultName()` |
| 工作流预算中断 | `AgentResult.maxStepsReached()` 为 `true`，且 `WorkflowRun.truncated()` 为 `true`（两者不再矛盾） |
| END 节点的出边 | 永远不会被执行（引擎在 END 处结束推进）→ 加载期打一条 WARN，见下 |
| 默认 Loop | `llm.agent.default-loop`（显式配置）→ `@LlmLoop(defaultLoop = true)`（多个候选按 `order()` 取最小）→ `dsh-minimal` |
| `@LlmLoop` / `@LlmGuard` 的扫描范围 | 按**主应用包**扫类路径，**不区分** `src/main` 与 `src/test`：写在主包下的测试夹具会进生产上下文（`@SpringBootTest` 里表现为"多出一堆 Loop/拦截器"）。测试夹具请放到主应用包之外 |

### HTTP 端点（需显式开启）

```yaml
llm:
  web:
    enabled: true        # 默认 false
    base-path: /llm
```

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/llm/agents` | 列出全部 Agent 及其 loop / model / 工具 |
| `GET` | `/llm/loops` | 列出全部 Loop |
| `GET` | `/llm/models` | 列出全部模型 |
| `POST` | `/llm/agents/{name}/chat` | 对话：**默认 JSON**；该 Agent 打开了流式（`@LlmAgent(stream = true)` 或 `llm.agent.stream=true`）时返回 **SSE** |
| `GET` | `/llm/agents/{name}/stream` | SSE 流式（`delta` / `thinking` / `tool_call` / `done` / `error`），响应头带 `charset=UTF-8` |

**端点的实际契约**（这些是"按直觉猜会猜错"的部分，写在这里免得只能读源码）：

| 场景 | 直觉预期 | 实际行为 |
|---|---|---|
| `POST /llm/agents/{不存在的名字}/chat` | 404 | **404**（返回 `{status, error, message, path}`） |
| 不传 `sessionId` | 每次调用独立 | **每次调用独立**（响应里的 `sessionId` 是新生成的 `http-<uuid>`）；要续接历史必须显式传 |
| `@LlmAgent` 不声明 `tools` / `toolNames` | "没有工具" | **继承容器的全局工具表** —— "什么都不写"等于"拿到所有工具"。想收紧请显式列出 `tools` / `toolNames` |
| 方法级 `@SystemPrompt` / `@Ctx` / `@User` | 在 HTTP 端点上生效 | **完全不生效**：端点按 Agent 名调用，不经过方法绑定，只认 Agent 级 `systemPrompt` |
| 运行失败 | 也会留一条记录 | 失败路径只广播 `onError`，**不产生** `agent_run` 行（需要在监听器里自行处理 `onError`） |
| SSE 事件里的 `tool_result` | 存在 | **不存在**：`LlmStreamHandler` 没有对应回调；工具结果只在 `done` 事件的 `toolCalls` 里 |
| SSE 里的中文 | 直接读就正常 | 响应头声明 `charset=UTF-8`（1.1.1 起）。此前 Spring 的 `StringHttpMessageConverter` 会按 ISO-8859-1 解码，中文必乱码 |
| `llm.agent.stream=true` | 让 `/chat` 变流式 | 1.1.1 起**确实如此**；此前是死配置（写与不写完全一样） |

> 默认关闭是刻意的：这是一个能触发模型调用、并可能间接驱动本地工具执行的入口，
> 不应该因为"引了依赖"就自动对外。开启前请自行加鉴权。

---

## 模块结构

```
benxin/
├── benxin-core/                  纯 Java，只依赖 Jackson + SLF4J，脱离 Spring 也能用
│   └── com.benxin.llm.core
│       ├── annotation/           @LlmAgent @LlmLoop @LlmTool @SystemPrompt ...
│       ├── message/ chat/        统一消息与请求/响应模型
│       ├── protocol/             三协议编解码 + SSE 解析
│       ├── transport/            HttpTransport + HttpLlmModel
│       ├── model/                LlmModel / ModelRegistry / ModelFactory
│       ├── tool/ sandbox/        工具抽象、反射适配、JSON Schema、沙箱、审批
│       ├── tool/builtin/         8 个内置工具
│       ├── loop/                 AgentLoop、AbstractAgentLoop、6 个内置 Loop
│       ├── agent/                Agent 运行时与装配器
│       ├── context/ memory/ prompt/ hook/   上下文、记忆、提示词、拦截器、监听器
│
├── benxin-spring-boot-starter/   Spring 集成
│   └── com.benxin.llm.spring
│       ├── LlmProperties         llm.* 配置绑定
│       ├── LlmAutoConfiguration  全部组件的条件装配
│       ├── LlmToolCatalog        @LlmTool 扫描（BeanPostProcessor）
│       ├── LlmAgentFactory       注解 + 配置 + 容器 → Agent
│       ├── LlmAgentRegistrar     @LlmAgent 接口扫描与代理注册
│       └── LlmAgentController    可选 HTTP/SSE 端点
│
└── benxin-examples/              可运行示例（内置离线 Mock 模型）
```

---

## 运行示例

```bash
cd benxin
mvn -o clean install          # 构建三个模块并装入本地仓库
cd benxin-examples
mvn -o spring-boot:run        # 启动即打印 7 个演示
```

或者直接跑打好的可执行包：

```bash
java -jar benxin-examples/target/benxin-examples-1.1.0.jar
```

示例默认使用**离线 Mock 模型**：不联网、不花钱，但完整跑通
"模型决策 → 工具调用 → 结果回灌 → 收尾"的全链路，含流式输出。
实测输出（节选）：

```
① 声明式调用 —— @LlmAgent 接口直接注入
   [mock] 第 1 次调用：历史 3 条，工具 3 个，已有工具结果=false
   [benxin] 调用工具 get_weather 参数={"city":"北京","unit":"celsius"}
   [benxin] Agent [weatherAssistant] 结束，2 步，67 ms，token 300/130

③ 自定义 Loop —— 一个 @LlmLoop 注解就被注册进框架
   [benxin] 注册自定义 Loop [step-by-step] ← ...StepByStepLoop
   [step-by-step] 模型复述：……
   会话历史条数: 7

④ 流式输出 —— 增量实时到达
   （逐字打印）共收到 195 个字符的增量

⑤ 强类型返回 —— 模型输出直接变成 Java 对象
   总评: 示例代码整体结构清晰……
   评分: 78
   拦截器统计: 运行 5 次 / 工具 3 次（仅 codeReviewer 生效）

⑥ 程序化组装 —— 不用注解也能换掉任意组件
   装配结果: Agent[manual-agent → react / mock / 工具 [get_current_time, calculate, get_weather]]

⑦ 声明式工作流 —— 图写在 YAML 里，模型只负责填内容
   路径: begin → classify → route → urgent → stamp-urgent → stamp → report
```

示例应用同时开启了 HTTP 层（`llm.web.enabled=true`），可直接验证：

```bash
curl http://localhost:8080/llm/agents
curl http://localhost:8080/llm/loops
curl -X POST http://localhost:8080/llm/agents/weatherAssistant/chat \
     -H "Content-Type: application/json" \
     -d '{"message":"北京天气怎么样？","sessionId":"demo"}'
curl -N "http://localhost:8080/llm/agents/weatherAssistant/stream?message=现在几点"
```

SSE 端点实测输出：

```
event:tool_call
data:{"name":"get_current_time","arguments":"{}"}

event:delta
data:{"text":"根据工具返回的结果："}

event:done
data:{"agent":"weatherAssistant","steps":2,"usage":{...},"toolCalls":[...]}
```

接真实模型：

```bash
set DEEPSEEK_KEY=sk-xxx
mvn -o spring-boot:run -Dspring-boot.run.profiles=real
```

示例覆盖：声明式调用、工具闭环、自定义 Loop、流式输出、强类型返回值、
程序化组装（不用注解也能换掉任意组件）、拦截器生效范围、**声明式工作流**。

---

## 设计取舍

**为什么"协议"与"厂商"要分开？**
因为二者正交。把 `OpenAiCodec` 与 `OpenAiModel` 分开后，接入 DeepSeek、通义、Ollama
只需要改 `base-url` 与 `model` 两个字段，而不是新增一个厂商适配类。

**为什么内置文件工具默认关闭？**
因为它们能真实改动宿主机。默认开启等于"引入一个依赖就把服务器交给模型"。
代价是多写两行配置，收益是误开的爆炸半径为零。

**为什么 `dsh-minimal` 只有几十行？**
它是这个框架的自证：如果连最核心的 Agent Loop 都能短到一眼看完，
那"一切皆可插拔"就不是口号——你完全可以自己写一个，而且不会比它复杂多少。

**为什么流式工具调用要在解码器里拼装？**
OpenAI 的 `tool_calls[].function.arguments` 是**按 index 分片到达**的，
Anthropic 用 `input_json_delta`，Gemini 则一次性给全。把这种协议差异挡在 `StreamDecoder`
之内，上层 Loop 才能只看到"一次完整的工具调用"。

**为什么 `@System` 不叫 `@System`？**
会与 `java.lang.System` 冲突，导致同一个文件里写不了 `System.out`。所以叫 `@SystemPrompt`。

**为什么 `SystemPromptProvider` 与 `Loop` 分离？**
因为提示词的生命周期（版本化、灰度、A/B）与循环逻辑完全不同。
分开后，claude-code 的循环可以配合任意提示词来源；反之亦然。

---

## 版本与升级

当前版本 **1.1.1**（上一个发布版本是 `v1.1.0`）。

### 1.1.1：31 条实测缺陷修复

由独立示例工程 `benxin_exp`（14 个模块、789 个用例）在 1.1.0 上逐条实测上报，
修复覆盖"**配置写了不生效**"与"**静默错到底**"两类问题：

| 组 | 修了什么 |
|---|---|
| **注解不再形同虚设** | `@LlmRetry` 真正生效（模型调用层 + 工具执行层，`includeTools` 控制是否连工具一起包）；`@LlmAgent(stream = true)` / `llm.agent.stream` 真正让 `/chat` 走 SSE；`@LlmLoop(defaultLoop = true)` 真的能成为默认 Loop，多个候选按 `order()` 取最小；`@LlmTool` 新增 `requiresApproval` / `parallelSafe` |
| **工具链路** | `@LlmToolParam(required = true)` 运行期真的校验（缺参回灌"缺少必填参数 [x]"）；`@LlmTool` 方法的 `AgentSession` 参数注入**真实会话句柄**（不再恒为 `null`）；`beforeTool` 短路不再 `break` 掉整条拦截器链；`@LlmAgent(tools = 外层类.class)` 能匹配到写在内部类里的手写 `ToolCallback`；schema 的 `default` 按类型转换、深度截断不再把标量降级成 `object` |
| **传输与 Web** | 流式路径收到 `200 + 非 SSE 整包` 时报可读错，不再静默返回空回答；SSE 端点声明 `charset=UTF-8`（中文不再乱码）；审批被拒时能把**理由**告诉模型（新增 `ApprovalHandler.ApprovalDecision` / `decide`） |
| **可观测性** | 同一次失败的 `onError` 只广播一次（此前 Loop 与 Agent 各发一次）；`SelectiveInterceptor` 暴露 `delegate()`；`AgentAttributes` 的 `SESSION_ID` / `AGENT_NAME` 真的被播种，其余常量与实现同源；`AgentRegistry.resolved()` 语义写清并新增 `resolveAll()`；注册器重复执行不再产生 `name#1` 影子 bean |
| **装配与覆盖** | `ToolSandbox` / `ApprovalHandler` 改为 `ObjectProvider` 注入：bean 名 = Agent 名优先，多实现不再直接启动失败；`AgentBuilder.model/loop` 的实例与名字之间改为**后设者胜**；`toBuilder()` 携带监听器 |
| **工作流** | END 节点的出边在加载期给出 WARN（此前是纯静默死代码）；`WorkflowIo` 把 flow 风格 YAML 被当成 JSON 的报错说清楚，并新增 `llm.workflows.<key>.format: yaml` |
| **上下文与记忆**（1.1.1 上半场） | `SlidingWindowCompactor` 重写：`keepRecent` 是**下限**（不再压缩到只剩 1 条、也不丢摘要）；压缩预算与 `ContextManager` 共用同一口径；`TokenEstimator` 对纯中文不再多算 1；`withText` 对工具消息是**替换**且保留 `error` / `toolUseId`；条件表达式里的 `${x:-兜底}` 真正生效 |

### ⚠️ 1.0.0 → 1.1.1 升级注意

**1.1.0 的行为变更**见上一节表格。**1.1.1 追加的行为变更**（同样朝"少一点静默"的方向，但会改变既有项目的可观测行为）：

| 位置 | 1.1.0 | 1.1.1 |
|---|---|---|
| `@LlmToolParam(required = true)` 的参数缺失 | 静默回落 `null` / `0` / `false` | **回灌 `缺少必填参数 [x]`**，工具不被执行 |
| `Optional<T>` 参数 | 出现在 schema 的 `required` 里 | **不再算必填**（类型本身已表达"可缺省"） |
| `@LlmTool` 方法的 `AgentSession` 参数 | 恒为 `null` | **注入真实会话句柄**；上下文不提供句柄时明确报错 |
| 失败的运行 | `onError` 广播 2 次 | **1 次**（只有最外层 `DefaultAgent.run` 广播） |
| `@LlmAgent(stream = true)` / `llm.agent.stream=true` | 无任何效果 | **`/chat` 返回 SSE**；不想要就关掉这个开关 |
| `llm.agent.default-loop` 的默认值 | `"dsh-minimal"`（导致注解永远无效） | **留空**（"没配"与"配了 dsh-minimal"区分开），回落链见上 |
| `ApprovalPolicy.ON_FAILURE` | 只覆盖沙箱拒绝（等于把沙箱降级） | 沙箱拒绝**不可覆盖**；改为**执行失败**后问一次、批准重试一次 |
| `AgentBuilder.model(LlmModel)` 后再 `.model("名字")` | 实例恒胜（名字静默无效） | **后设者胜**（沿用 README 一直承诺的语义） |
| `Agent.interceptors` 里 `beforeTool` 短路 | 后续拦截器完全收不到这次调用 | 后续拦截器仍收到 `beforeTool` / `afterTool`，第一个非空结果生效 |
| `LlmAgentFactory` 构造签名 | `ToolSandbox` / `ApprovalHandler` 裸类型 | `ObjectProvider<...>`；**直接 new 过它的代码需要改**（容器装配不受影响） |
| `ToolContext` | 无 `session()` | 新增 `default Optional<AgentSession> session()`（**自定义实现无需改动**，默认返回空） |
| `ApprovalHandler` | 只有 `approve()` | 新增 `default decide()` + `ApprovalDecision` 记录（**已有实现无需改动**） |
| 注册器重复执行 | 产生 `name#1`，随后 `getBean(接口.class)` 抛 `NoUniqueBeanDefinitionException` | **跳过重复项 + WARN** |
| `WorkflowIo` 解析 | flow 风格 YAML 报"非法 JSON" | 报错说明"这是 YAML 的 flow 风格"并给出改法 |
| `llm.workflows.<key>.format` | 不存在 | 新增（`auto` / `yaml` / `json`） |

### 1.1.0 新增

| 项 | 说明 |
|---|---|
| **四个内置协议** | 除 openai / anthropic / gemini 外新增 **OpenAI Responses API**（`protocol: responses`）—— `input[]` item 列表、顶层 `instructions`、扁平 `tools[]`、命名 SSE 事件 |
| **协议扩展点** | `Protocol` 由封闭枚举改为**可扩展标识类型** + 新增 `ProtocolRegistry`：注册一个 `ProtocolCodec` bean 就能在配置里写 `protocol: bedrock`，见[加自己的协议](#加自己的协议) |
| **工作流模式** | 两个新 Loop：`workflow`（声明式图编排，六种节点 + 条件表达式 + `${}` 模板）与 `staged`（规划 → 执行 → 验收 → 修复 → 汇总） |
| **枚举之外的 Loop** | 内置 Loop 增至 8 个（`dsh-minimal` `react` `claude-code` `codex` `plan-execute` `reflexion` `workflow` `staged`） |
| **子代理可观测** | 子代理用量补记进父上下文；新增 `SubAgentObserver` 观察点，自定义 Loop 能在真实派生路径上收到通知 |
| **46 条实测缺陷修复** | 由独立集成测试工程 `benxin_test`（493 个测试）逐条实测并复验 |

### ⚠️ 升级注意

**源码级破坏性变更（`Protocol` 由 enum 改为类）：**

| 写法 | 影响 |
|---|---|
| `Protocol.OPENAI` 等常量 | ✅ 不变 |
| `switch (protocol) { case OPENAI -> ... }` | ❌ 不再可编译（枚举专用语法） |
| `Protocol.values()` | ❌ 改为 `Protocol.builtins()` / `Protocol.builtinIds()` |
| `EnumSet<Protocol>` / `ordinal()` | ❌ 不再适用 |
| `Protocol.from("未知")` 抛异常 | ⚠️ 不再抛（改为接受任意非空 id，未注册的协议在装配期报错并列清单） |
| `Protocol.of(null / "")` | ⚠️ 现在**抛异常**（不再回落 `openai`）；配置项缺省仍由绑定层兜底为 `openai` |

**行为变更**（都朝着"少一点静默、多一点如实"的方向，但会改变既有项目的可观测行为）：

| 位置 | 1.0.0 | 1.1.0 |
|---|---|---|
| `@EnableLlmAgents` | README 说可省 | **必写**（不写则一个 `@LlmAgent` 接口都不注册） |
| 默认沙箱 | `builtin-enabled=false` 时是 `permissive` | **真正的围栏**：自己写的工具同样受 `PathSandbox` 约束 |
| 沙箱拒绝 vs 审批 | `ON_FAILURE` + 会批准的 handler 可推翻 | 沙箱拒绝是**硬约束**，不可被审批覆盖 |
| `llm.models.<key>.model` | 被注册名覆盖，配置项不生效 | **真的下发到上游** |
| `llm.tools.denied-commands` | 整体替换内置黑名单 | **追加**在内置那批之上 |
| `llm.tools.enabled` | 增量（叠加只读基线） | 不变，但点名写/执行类工具即等于授权（不再"看得见跑不动"） |
| 未知 Agent 名的 HTTP 请求 | 500 | **404** |
| 不传 `sessionId` 的 HTTP 调用 | 共享 `<agent>-default` 会话 | **每次一个独立会话**（要续接历史请显式传） |
| `AgentSpec.Builder.maxSteps(<=0)` | 静默回落 24 | **直接报错** |
| `llm.agent.hard-max-steps` | 只夹注解路径 | 下沉到 `AgentBuilder.build()`（程序化装配需自己调 `.hardMaxSteps(n)`） |
| 工作流 tool 节点的失败 | 当成该节点的正常输出流下去 | **节点级失败**，触发 `retry` / `continueOnError` |
| Gemini 流式用量 | 累计值被逐帧透传（数字偏大） | 换算成增量，与非流式一致 |

---

## 测试

```bash
cd benxin
mvn -o test
```

**244 个测试全部通过**（benxin-core 191 / starter 39 / examples 14，共 19 个测试类），
全部离线、不消耗 token：

### 1.1.1 的验证方式

| 层 | 手段 | 结果 |
|---|---|---|
| 单元 / 装配 | 本仓库 244 个离线用例 | 全绿 |
| 集成 | 独立工程 `benxin_test`（9 个模块、494 个用例，含真实 Spring 上下文、H2、HTTP 端点） | 全绿 |
| **真实模型端到端** | LM Studio 上的 `qwen/qwen3-vl-8b`（OpenAI 兼容端点），走 **core 层**（流式增量 / 工具调用 / 必填参数回灌 / `AgentSession` 注入）与 **Spring HTTP 层**（`/chat`、`/stream`、SSE 字节级 charset、审批拒绝理由回灌） | 见下 |

真实模型这一轮的实测结论（本地小模型反而更能暴露"静默失效"类问题）：

```
流式：拿到真实增量、onStart/onComplete 各恰好一次
工具：模型按 schema 调用 get_weather(city=北京) → 执行成功 → 回答里带出 21℃
必填：args={} → "缺少必填参数 [city]，请补齐后重试"
会话：工具方法里的 AgentSession 拿到 lms-session-42（不再是 null）
Web ：POST /chat（@LlmAgent(stream=true)）→ Content-Type: text/event-stream; charset=UTF-8
     GET  /stream  → Content-Type: text/event-stream;charset=UTF-8（字节级确认是 UTF-8，中文不乱码）
审批：requiresApproval=true 的工具被拒 → done 事件里
     result="用户拒绝执行工具 [delete_file]：演示环境禁止删除文件，请改为只读操作"
     模型据此改写回答，把理由原样告诉了用户
```

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `OpenAiCodecTest` | 7 | 端点拼接、消息与工具编码、多模态、`tool_calls` 解码、**流式 arguments 分片拼装** |
| `AnthropicCodecTest` | 11 | 顶层 system、`max_tokens` 兜底、`input_schema`、**连续工具结果合并进同一 user 回合**、thinking block、`input_json_delta` 拼装 |
| `GeminiCodecTest` | 9 | `:generateContent` / `:streamGenerateContent`、systemInstruction、**schema 清洗**、`functionResponse` 按名对位、安全拦截映射 |
| `ResponsesApiCodecTest` | 18 | 顶层 `instructions`、`input` item 摊平（message / function_call / function_call_output / input_image）、**扁平 `tools[]`**、`max_output_tokens`、`status` → 结束原因、命名 SSE 事件与**无 `[DONE]`** 的收尾、工具参数按 `output_index` 分片 |
| `ProtocolRegistryTest` | 10 | `Protocol` 可扩展标识类型与实例驻留、注册表解析、**自定义协议端到端装配**、同名覆盖内置、未注册协议的快速失败 |
| `SseParserTest` | 6 | 三协议 SSE 差异、多行 data、`[DONE]`、注释行、CRLF、尾事件冲刷 |
| `ToolScannerTest` | 6 | 注解扫描、方法名兜底、参数绑定与默认值、`ToolContext` 注入、异常转错误、DTO 序列化 |
| `JsonSchemaGeneratorTest` | 6 | 类型映射、枚举、集合、嵌套 POJO、`Optional` 解包、required 规则 |
| `AgentRuntimeTest` | 18 | 工具闭环、未知工具、沙箱拒绝、审批拒绝与放行、拦截器短路与改写、`returnDirect`、步数上限、记忆、生命周期事件、**并行结果顺序**、超时、结果截断、用量累计、`toBuilder` 隔离 |
| `ConditionsTest` | 28 | 工作流条件表达式：全部运算符、`not/and/or` 与括号、`is empty` / `exists` / `missing`、语法错误提示 |
| `WorkflowEngineTest` | 21 | 六种节点、边序即优先级、回边循环、`maxVisitsPerNode` 护栏、节点 `retry`、`continueOnError` 补偿、预算中断 |
| `WorkflowIoTest` | 18 | YAML/JSON 读写往返、缺字段与非法字段的加载期报错、`retry` / `maxStepsPerNode` 解析 |
| `StagedLoopTest` | 10 | 规划 → 执行 → 验收 → 修复 → 汇总；关掉验收后退化为「带汇总的 plan-execute」 |
| `WorkflowLoopTest` | 9 | `workflow:<name>` Loop 的装配、属性传定义、运行结果写回 attributes |
| `TemplatesTest` | 8 | `${x}`、`${x:-兜底}`、"取不到保留原文"、参数渲染 |
| `PlanParserTest` | 6 | 编号计划的容错解析（多种编号与缩进形态） |
| `LlmAutoConfigurationTest` | 22 | 条件装配、内置 Loop、用户 bean 覆盖（记忆/压缩器/监听器）、`llm.enabled=false`、内置工具开关与沙箱联动、工具目录、自定义 Loop 注册、**协议注册表（内置四协议 / Codec bean 被收进注册表 / 同名覆盖内置 / 启动期可读失败）** |
| `LlmAgentProxyTest` | 17 | **注解接口 → 代理 → 运行时 → 模型** 全链路：参数语义、`@Ctx` 不泄漏、`@SystemPrompt` 渲染、DTO 返回、流式回调、多轮记忆、工具装配、单例与 Object 方法 |
| `BenxinExampleApplicationTest` | 14 | 示例应用装配：6 个 Agent、7 个 Loop、离线模型、工具扫描、安全默认值、声明式工作流 |

---

## License

MIT License —— 见 [LICENSE](LICENSE)。
