# 本心 · benxin

> 一个 Spring Boot 插件：**声明式**地调用 LLM，并且**一切皆可替换**。

`本心` 取自"万变不离其宗"——模型、协议、循环、工具、上下文、记忆、提示词全都可以被换掉，
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

- [核心主张：一切皆可替换](#核心主张一切皆可替换)
- [快速开始](#快速开始)
- [注解速查](#注解速查)
- [三种主流 LLM API 协议](#三种主流-llm-api-协议)
- [内置 Agent Loop](#内置-agent-loop)
- [工具](#工具)
- [替换任意一个切面](#替换任意一个切面)
- [可观测性](#可观测性)
- [模块结构](#模块结构)
- [运行示例](#运行示例)
- [设计取舍](#设计取舍)

---

## 核心主张：一切皆可替换

框架里没有一个"必须用我的实现"的地方。每个环节都是 `接口 + 内置实现 + 覆盖点`：

| 切面 | 接口 | 内置实现 | 怎么换掉 |
|---|---|---|---|
| 模型 | `LlmModel` | `OpenAiModel` / `AnthropicModel` / `GeminiModel` | 定义 `LlmModel` bean，或写 `llm.models.*` |
| 协议 | `ProtocolCodec` | `OpenAiCodec` / `AnthropicCodec` / `GeminiCodec` | 定义 `ProtocolCodec` bean |
| 传输 | `HttpTransport` | `JdkHttpTransport`（零依赖） | 定义 `HttpTransport` bean（OkHttp / WebClient / 测试桩） |
| **循环** | `AgentLoop` | `dsh-minimal` `react` `claude-code` `codex` `plan-execute` `reflexion` | `@LlmLoop("名字")` |
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

**替换机制统一是**：定义一个同类型 bean。所有 `@Bean` 都带 `@ConditionalOnMissingBean`，
所以你不需要开关、不需要排除自动配置、更不需要 fork。

---

## 快速开始

### 1. 依赖

```xml
<dependency>
    <groupId>com.benxin</groupId>
    <artifactId>benxin-spring-boot-starter</artifactId>
    <version>1.0.0</version>
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
      model: deepseek-chat
```

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
| `@LlmLoop("name")` | 类 | 注册一个自定义 Agent 循环（**无需再补 `@Component`**，主包下会被自动发现） |
| `@LlmModelDef("name")` | 类 | 用指定名字注册一个 `LlmModel` bean |
| `@LlmTool` | 方法/类 | 把方法暴露给模型调用 |
| `@LlmToolParam` | 参数 | 描述工具参数的名称/说明/是否必填/默认值 |
| `@SystemPrompt` | 方法 | 方法级系统提示词，支持 `{变量}` 插值（可引用 `@User` 参数与 `@Ctx` 变量） |
| `@User` / `@Assistant` | 参数 | 指定参数作为 user / assistant 消息（**无注解参数默认即 user**） |
| `@Ctx("name")` | 参数 | 上下文变量：只参与提示词渲染与运行属性，不进消息体 |
| `@Memory` | 参数 | 参数值作为 sessionId，自动装载/保存会话历史 |
| `@LlmGuard` | 类 | 声明式注册拦截器，可限定只对某些 Agent 生效（同样无需 `@Component`） |
| `@LlmRetry` | 方法/类 | 声明重试策略 |
| `@EnableLlmAgents` | 配置类 | 指定 `@LlmAgent` 扫描范围（不写则从主应用包推断） |

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

---

## 三种主流 LLM API 协议

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
**无 tool id 情况下的 id 合成与结果对位**、`generationConfig`、
**JSON Schema 清洗**（剔除 Gemini 不接受的 `additionalProperties` 等字段）、
`thought` 标记与 `usageMetadata`。

### 想加第四种协议？

```java
@Component
public class MyBedrockCodec implements ProtocolCodec {
    @Override public Protocol protocol() { return Protocol.from("bedrock"); }
    // endpoint / headers / encode / decode / newStreamDecoder
}
```

注册后即可在配置里写 `protocol: bedrock`。**调用方代码一行都不用改。**

---

## 内置 Agent Loop

| 名字 | 设计来源 | 特征 |
|---|---|---|
| `dsh-minimal` | DSH 极简 | `调模型 → 有工具就执行 → 再调模型`，约 80 行。零内置工具、零压缩，**故意什么都不多做**，是"一切可替换"的最小样板 |
| `react` | 经典 ReAct | Thought → Action → Observation；**模型不支持 function calling 时自动降级到文本协议**（解析 `Action:` / `Action Input:` / `Final Answer:`） |
| `claude-code` | Claude Code | 单一主循环 + **子代理派生**（独立上下文、只回传结论）+ 待办管理 + **自动上下文压缩** + 连续工具错误熔断 |
| `codex` | OpenAI Codex | **turn 制**：`update_plan` 出计划 → `apply_patch` 做最小改动 → 命令验证；**如实告知沙箱权限边界**；未验证则给一次补验证的机会 |
| `plan-execute` | 经典范式 | 第一步强制产出编号计划，之后逐步推进并回显进度 |
| `reflexion` | 经典范式 | 执行 → 自省评分 → 低于阈值则带着批评重做 |

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
    allowed-commands: []        # 非空则命令必须以此开头（白名单）
    denied-commands: []         # 命中即拒绝（黑名单，内置一批危险命令）
    exec-timeout: 60s
    max-output-chars: 30000
```

即便全部打开，`PathSandbox` 仍会拦住目录穿越、`.git/`、`.env`、`*.pem`、`id_rsa` 等敏感路径，
`CommandSandbox` 仍会拦住 `rm -rf /`、`mkfs`、fork bomb 等模式。

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
| `POST` | `/llm/agents/{name}/chat` | 同步对话 |
| `GET` | `/llm/agents/{name}/stream` | SSE 流式（`delta` / `thinking` / `tool_call` / `done` / `error`） |

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
mvn -o spring-boot:run        # 启动即打印 6 个演示
```

或者直接跑打好的可执行包：

```bash
java -jar benxin-examples/target/benxin-examples-1.0.0.jar
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
程序化组装（不用注解也能换掉任意组件）、拦截器生效范围。

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
那"一切皆可替换"就不是口号——你完全可以自己写一个，而且不会比它复杂多少。

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

## 测试

```bash
cd benxin
mvn -o test
```

**101 个测试全部通过**（benxin-core 63 / starter 29 / examples 9），全部离线、不消耗 token：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `OpenAiCodecTest` | 7 | 端点拼接、消息与工具编码、多模态、`tool_calls` 解码、**流式 arguments 分片拼装** |
| `AnthropicCodecTest` | 11 | 顶层 system、`max_tokens` 兜底、`input_schema`、**连续工具结果合并进同一 user 回合**、thinking block、`input_json_delta` 拼装 |
| `GeminiCodecTest` | 9 | `:generateContent` / `:streamGenerateContent`、systemInstruction、**schema 清洗**、`functionResponse` 按名对位、安全拦截映射 |
| `SseParserTest` | 6 | 三协议 SSE 差异、多行 data、`[DONE]`、注释行、CRLF、尾事件冲刷 |
| `ToolScannerTest` | 6 | 注解扫描、方法名兜底、参数绑定与默认值、`ToolContext` 注入、异常转错误、DTO 序列化 |
| `JsonSchemaGeneratorTest` | 6 | 类型映射、枚举、集合、嵌套 POJO、`Optional` 解包、required 规则 |
| `AgentRuntimeTest` | 18 | 工具闭环、未知工具、沙箱拒绝、审批拒绝与放行、拦截器短路与改写、`returnDirect`、步数上限、记忆、生命周期事件、**并行结果顺序**、超时、结果截断、用量累计、`toBuilder` 隔离 |
| `LlmAutoConfigurationTest` | 12 | 条件装配、6 个内置 Loop、用户 bean 覆盖（记忆/压缩器）、`llm.enabled=false`、内置工具开关与沙箱联动、工具目录、自定义 Loop 注册 |
| `LlmAgentProxyTest` | 17 | **注解接口 → 代理 → 运行时 → 模型** 全链路：参数语义、`@Ctx` 不泄漏、`@SystemPrompt` 渲染、DTO 返回、流式回调、多轮记忆、工具装配、单例与 Object 方法 |
| `BenxinExampleApplicationTest` | 9 | 示例应用装配：4 个 Agent、7 个 Loop、离线模型、工具扫描、安全默认值 |

---

## License

MIT License —— 见 [LICENSE](LICENSE)。
