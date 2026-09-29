# 本心 · 架构说明

本文说明 benxin 的内部结构与关键设计决策。面向两类读者：想改框架源码的人，
以及想在此基础上写扩展的人。

---

## 一、设计原点

绝大多数 LLM 调用库把"怎么调"写死在代码里：一个 `ChatClient`、一种消息格式、
一套固定的工具调用流程。用户能改的只有"提示词"和"参数"。

本心反过来：**把每一个环节都做成可插拔的接口，然后用一层极薄的编排把它们连起来**。

有一个刻意的自我约束贯穿全文：

> 如果某个能力无法被替换，那它就不该出现在框架里。

最能说明这一点的是 `dsh-minimal` 循环 —— 它的全部实现只有一行：

```java
protected LoopResult doRun(LoopContext ctx) {
    return runToolCallingLoop(ctx);
}
```

Agent 循环是这类框架里最"核心"、通常也最臃肿的部分。当它小到可以一眼看完时，
"你完全可以自己写一个"才不是一句客套话。

---

## 二、分层

```
┌────────────────────────────────────────────────────────────────┐
│  用户代码                                                       │
│  @LlmAgent 接口 │ @LlmTool 方法 │ @LlmLoop 类 │ 自定义 Bean     │
└───────────────────────────┬────────────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────────────┐
│  benxin-spring-boot-starter                                    │
│  LlmProperties（llm.* 绑定）                                     │
│  LlmToolCatalog（BeanPostProcessor：扫描 @LlmTool / ToolCallback）│
│  LlmAgentRegistrar → LlmAgentFactoryBean → 动态代理             │
│  LlmAgentFactory（注解 + 配置 + 容器 → Agent）                   │
│  LlmAutoConfiguration（全部 @ConditionalOnMissingBean）          │
└───────────────────────────┬────────────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────────────┐
│  benxin-core（零 Spring 依赖）                                   │
│                                                                │
│   ┌──────────┐   ┌──────────────┐   ┌──────────────────────┐   │
│   │ Agent    │──▶│ AgentLoop    │──▶│ LoopContext          │   │
│   │ 运行时    │   │ 6 个内置实现  │   │ （模型/工具/沙箱/审批）│   │
│   └────┬─────┘   └──────────────┘   └──────────┬───────────┘   │
│        │                                        │              │
│        │           ┌────────────────────────────▼───────────┐  │
│        └──────────▶│ LlmModel → ProtocolCodec → HttpTransport│  │
│                    │            （三协议）    （可换 HTTP 库） │  │
│                    └────────────────────────────────────────┘  │
│                                                                │
│  ContextManager · MemoryStore · SystemPromptProvider           │
│  AgentInterceptor · AgentListener · ToolSandbox · ApprovalPolicy│
└────────────────────────────────────────────────────────────────┘
```

`benxin-core` 不依赖 Spring，因此可以被用在批处理、CLI、测试，甚至别的框架里。
门面类 `Benxin` 就是为这种用法准备的。

---

## 三、一次调用的完整生命周期

以 `@LlmAgent` 接口上的一次方法调用为例：

```
用户: assistant.ask("北京天气")
  │
  ├─ 1. LlmAgentInvocationHandler.invoke
  │     ├─ 解析方法上的注解 → 参数绑定表（首次调用时缓存）
  │     ├─ @User / 无注解   → user 消息
  │     ├─ @Assistant       → 预填充的 assistant 消息
  │     ├─ @Ctx             → 模板变量 + 运行属性（不进消息体）
  │     ├─ @Memory          → sessionId
  │     └─ LlmStreamHandler → 流式出口
  │
  ├─ 2. 渲染方法级 @SystemPrompt（若有）→ 放入 AgentAttributes.SYSTEM_PROMPT
  │     没有声明则交给 SystemPromptProvider，不覆盖 —— 保证 provider 可插拔
  │
  ├─ 3. DefaultAgent.call
  │     ├─ 装载记忆（spec.memory() 为真时）
  │     ├─ 构造 AgentInvocation
  │     ├─ 拦截器 beforeAgent（按 order 升序）
  │     └─ 选 Loop → DefaultLoopContext（持有本轮全部协作者）
  │
  ├─ 4. AgentLoop.run(ctx)                ← 智能在这里，且只在这里
  │     └─ ctx.callModel()
  │           ├─ ContextManager.prepare（拼系统提示、必要时压缩）
  │           ├─ 拦截器 beforeModel（可改写请求）
  │           ├─ LlmModel.stream
  │           │     └─ ProtocolCodec.encode → HttpTransport.postStreaming
  │           │           └─ SseParser → StreamDecoder → 统一回调
  │           ├─ StreamCollector 聚合出完整 ChatResponse
  │           ├─ 拦截器 afterModel
  │           └─ 用量累计 + 监听器 onStepEnd
  │
  ├─ 5. 有工具调用 → ctx.executeTools(…)   ← 五道关卡，见第六节
  │
  ├─ 6. 收尾：写回记忆、拦截器 afterAgent、监听器 onAgentEnd
  │
  └─ 7. 返回值按方法声明的类型自适应
        String / AgentResult / void / Optional / List<ChatMessage>
        / int（步数）/ long（耗时）/ boolean / 任意 DTO（JSON 反序列化）
```

关键点：**第 4 步之外没有任何"智能"**。步数预算、什么时候调模型、什么时候执行工具、
什么时候收尾，全部由 `AgentLoop` 决定；其余环节只负责把能力备好。

---

## 四、可插拔点与装配顺序

`LlmAutoConfiguration` 里每个 `@Bean` 都带 `@ConditionalOnMissingBean`。
这不是样板代码，而是"用户 bean 优先"的唯一实现手段：

| 顺序 | Bean | 默认实现 | 覆盖效果 |
|---|---|---|---|
| 1 | `HttpTransport` | `JdkHttpTransport` | 换 OkHttp / WebClient / 测试桩 |
| 2 | `LlmToolCatalog` | 扫描 `@LlmTool` 与 `ToolCallback` | 换工具来源（MCP、数据库） |
| 3 | `ModelRegistry` | yml 配置 + `LlmModel` bean | 自定义模型路由/降级 |
| 4 | `LoopRegistry` | 6 个内置 + `@LlmLoop` bean | 换或加 Loop |
| 5 | `TokenEstimator` | 中英混排启发式 | 接真实 tokenizer |
| 6 | `ContextCompactor` | `SummarizingCompactor` | 换 `SlidingWindowCompactor` 或自研 |
| 7 | `ContextManager` | `DefaultContextManager` | 换窗口策略 |
| 8 | `MemoryStore` | `InMemoryMemoryStore` | 换 Redis / JDBC |
| 9 | `SystemPromptProvider` | `TemplateSystemPromptProvider` | 接提示词管理平台 |
| 10 | `ApprovalHandler` | `autoApprove` | 接人工审批 |
| 11 | `ToolSandbox` | 内置工具关闭时 `permissive` | 换容器化隔离 |
| 12 | `AgentListener` | `LoggingListener` | 接可观测性 |
| 13 | `List<AgentInterceptor>` | 空 | 加日志/脱敏/限流/计费 |
| 14 | `AgentRegistry` | 空表 | — |
| 15 | `LlmAgentFactory` | 注解+配置+容器装配 | 换装配逻辑 |

`LlmToolCatalog` 用 **static `@Bean` 方法**注册，因为它本身是 `BeanPostProcessor`，
必须早于普通 bean 实例化，否则会出现"先创建的 bean 扫不到工具"的时序问题。

---

## 五、为什么把「协议」和「厂商」分开

```
LlmModel  =  ProtocolCodec  +  ModelConfig  +  HttpTransport
             （怎么说话）        （跟谁说）      （怎么送达）
```

三者正交，于是：

- **换厂商**：只改 `base-url` 与 `model` 两个字段。DeepSeek、通义千问、Kimi、GLM、
  vLLM、Ollama、OneAPI 全都复用 `OpenAiCodec`。
- **换协议**：换 `ProtocolCodec`，请求地址、认证头、消息结构、SSE 格式一起换掉。
- **换网络栈**：换 `HttpTransport`，业务代码零感知。

这个划分带来的直接收益是**可测试性**：把 `HttpTransport` 换成假实现，
整条模型链路（Codec → HttpLlmModel → Loop → Agent）就能在完全离线的环境下跑通。
本心的协议契约测试正是这么做的 —— 可测试性不是事后补的，是设计出来的。

### 三协议的主要差异（都在 Codec 里被抹平）

| 维度 | OpenAI | Anthropic | Gemini |
|---|---|---|---|
| 系统提示 | messages 里的 system 角色 | 顶层 `system` 字段 | 顶层 `systemInstruction` |
| assistant 叫法 | `assistant` | `assistant` | **`model`** |
| 工具声明 | `tools[].function.parameters` | `tools[].input_schema` | `tools[].functionDeclarations[].parameters` |
| 工具结果 | 独立的 `tool` 消息 | user 消息里的 `tool_result` block | user 侧的 `functionResponse` |
| 工具 id | 有 | 有 | **没有，需合成** |
| 流式工具参数 | `arguments` 按 index 分片 | `input_json_delta` 增量 | 一次性给全 |
| 流式结束 | `data: [DONE]` | `message_stop` 事件 | 流关闭 |
| 思维链 | `reasoning_content` | `thinking` block + signature | `part.thought` |

**最容易被忽略的两条约束**（都在代码里写了原因）：

1. Anthropic 要求连续的工具结果必须合并在**同一个 user 消息**的多个 `tool_result`
   block 里，且不允许出现连续两条同角色消息 —— 因此编码时按 `(角色, block)` 流做顺序合并，
   而不是"一条内部消息直译成一条协议消息"。
2. Gemini 的 `functionCall` 没有 id，工具结果靠**工具名**对位；本心会合成
   `call_<n>` 作为内部 id，但 `functionResponse` 只回传 `name`。

---

## 六、一次工具调用的五道关卡

`DefaultLoopContext.executeOne` 是安全与可观测性的收口点：

```
ToolUsePart（模型请求）
  │
  ├─ ① 拦截器 beforeTool      → 返回非 null 即短路，不再执行
  ├─ ② ToolSandbox.check      → 路径围栏 / 命令白名单 / 写权限闸门
  ├─ ③ ApprovalPolicy + Handler
  │      NEVER      从不询问
  │      ON_REQUEST 工具 requiresApproval() 为真才问（默认）
  │      ALWAYS     每次都问
  │      ON_FAILURE 被沙箱拒绝后再问一次
  ├─ ④ 执行（带超时；并行调用乱序完成但按声明顺序记账）
  └─ ⑤ 结果截断 → 拦截器 afterTool → 监听器 → 审计记录
```

任何一步失败都变成 `ToolResult.error(中文原因)` **回灌给模型**，而不是抛异常。
原因是：模型看到"路径越界了"会自己换一种做法，而抛异常只会让整条链断掉，
把一个可自愈的问题变成一个必须人工介入的故障。

---

## 七、声明式代理是怎么工作的

```
@LlmAgent 接口
  │
  ├─ LlmAgentRegistrar（ImportBeanDefinitionRegistrar）
  │     └─ ClassPathScanning 扫描 @LlmAgent 接口
  │         · 覆写 isCandidateComponent(AnnotatedBeanDefinition) 让接口成为候选
  │           （Spring 默认排除接口）
  │         · 扫描范围：@EnableLlmAgents(basePackages) 或主应用包
  │
  ├─ 每个接口注册一个 LlmAgentFactoryBean
  │     └─ getObject() → JDK 动态代理 + LlmAgentInvocationHandler
  │         · 同时 registerLazy(agentName, handler::agent)
  │           于是"还没被调用过"的 Agent 也能出现在 /llm/agents 里、
  │           也能被子代理按名解析到
  │
  └─ LlmAgentInvocationHandler
        · 方法 → MethodBinding（首次调用时分析并缓存）
        · 参数注解 → 消息 / 模板变量 / sessionId / 流式出口
        · 调 Agent → 按返回类型自适应
```

两个容易踩的坑，代码里都有注释：

- 方法**没有** `@SystemPrompt` 时不能生成一份"与 Agent 级相同"的覆盖值下发，
  否则用户自定义的 `SystemPromptProvider` 会在声明式路径上被静默绕过。
- 子代理关系可以是环状的（A 派生 B，B 派生 A），而 Agent 是不可变对象。
  因此子代理用 `LazyAgent` 包一层，把解析推迟到第一次真正调用。

---

## 八、内置 Loop 的还原度

| Loop | 还原了什么 | 做了哪些 Java 化取舍 |
|---|---|---|
| `claude-code` | 主线循环、子代理派生（独立上下文只回传摘要）、待办管理、自动上下文压缩、连续工具错误熔断、分段的系统提示 | 压缩在 Loop 层做（`ContextManager` 只在组装请求时生效，历史本身仍会增长），把较早的工具结果替换为占位摘要 |
| `codex` | turn 制「计划 → 补丁 → 验证」、`apply_patch` 专用格式解析、**如实告知沙箱权限边界**、未验证则给一次补验证的机会 | 补丁应用带唯一匹配校验与 CRLF 保持；权限边界写进提示词而不是靠模型猜 |
| `dsh-minimal` | 全部：就是标准循环骨架 | 无 —— 这正是它的意义 |
| `react` | 结构化 tool calling 与文本 `Action/Action Input/Final Answer` 双模 | 格式说明作为第一条 user 消息注入（系统提示此时已渲染完毕） |
| `plan-execute` | 先规划后执行、逐步回显进度 | 计划走完后追加一轮汇总，否则最终答案只有最后一步 |
| `reflexion` | 执行 → 自省评分 → 带批评重做 | 解析不到分数时判定为达标终止 —— 无分数无批评的重试只是空转烧 token |
| `workflow` | 声明式工作流：节点与边写在 YAML/JSON 里 | 见下节；控制流由条件表达式决定，而不是模型的自述 |
| `staged` | 结构化编排：规划 → 执行 → 校验 → 修复 → 汇总 | 比 `plan-execute` 多一道验收闸门；校验结论解析不出来时判为通过 |

两个 Loop 都**不引用任何具体工具类**，工具名用字符串判断。
这样它们才能与任意工具集组合，也才谈得上"一切可插拔"。

### 工作流模式：两条路线，一套分层

"工作流"这个词容易被当成一件事，其实是两件：**图由外部定义**，还是**流程由代码固化**。
本心两条都给了，因为它们各自解决不同的问题，而不是同一问题的两种写法。

```
core/workflow/                      ← 领域模型 + 引擎，不依赖任何框架类型
  WorkflowDefinition / Node / Edge    图本身（纯数据，可序列化、可断言、可静态分析）
  WorkflowState                       运行期黑板（变量 + input / last / error / current）
  Templates / Conditions              ${} 插值 与 边条件表达式（含加载期语法校验）
  WorkflowRuntime                     四个方法的副作用接口：callAgent / callTool / emit / outOfBudget
  WorkflowEngine                      按图推进（分支、循环、重试、预算、护栏）
  WorkflowIo                          YAML（SnakeYAML，可选）与 JSON 两条入口，共用一套校验

core/loop/                          ← 适配层，唯一与 Agent 运行时耦合的地方
  LoopContextRuntime                 把 WorkflowRuntime 转发到 LoopContext
  WorkflowLoop                       @LlmLoop("workflow")，取定义 → 备黑板 → 跑引擎
  StagedLoop                         @LlmLoop("staged")，阶段机写死在代码里
```

**为什么引擎要独立成一个不依赖框架的包？** 因为"编排"和"接入 Agent"的变化速度完全不同。
引擎只依赖四方法的 `WorkflowRuntime`，于是它的全部行为 —— 走错边、绕不出来、失败没兜住 ——
都能用一个假运行时精确断言，不需要模型也不需要网络；而适配层可以随框架演进而改，
不必重新验证编排逻辑。**代价是多了一个接口**，收益是两条路线共用同一套执行语义。

**三道防线，针对三种不同的失控**（这是引擎里最值得说的部分）：

1. **步数预算**拦住"模型一直在调工具"—— 只有调模型才消耗步数；
2. **单节点访问上限**拦住"不调模型的死循环"。`branch ↔ set` 互指这类环一个 token 都不烧，
   却能把 CPU 跑满，**预算机制对它完全无效**，因此必须有第二个独立的闸门；
3. **节点级 retry / continueOnError** 把"某个工具偶发失败"与"整张图崩掉"分开 ——
   后者还能让出边读 `${error}` 走补偿分支。

**两条路线共用的语义约定**：出边按声明顺序取第一条成立的（顺序即优先级，不需要 `priority` 字段）；
`branch` / `set` 不产生输出，因此不覆盖 `${last}`；
节点 id 不允许与 `input` / `last` / `error` / `current` 重名（否则会出现最难查的那类遮蔽 bug）；
解析不到的占位符保留原文而不是替换成空串（让拼写错误可见）。

---

## 九、安全模型

内置文件/命令工具能真实改动宿主机，因此默认**三重关闭**：

```
llm.tools.builtin-enabled=false   ← 总开关
llm.tools.allow-write=false       ← 写文件
llm.tools.allow-exec=false        ← 执行命令
```

即便全部打开，仍有两道机器级防线：

- **`PathSandbox`**：所有路径解析到真实路径后必须落在 `workdir` 之内；
  拒绝 `.git/`、`.env`、`id_rsa`、`*.pem`、`*.key`、`credentials*` 等 20 类敏感路径规则。
- **`CommandSandbox`**：`allow-exec` 总闸 → 黑名单（29 条危险模式）→ 白名单前缀。
- **`ApprovalPolicy`**：写类工具默认 `requiresApproval() = true`。

此外，**每个内置工具内部还会再自查一遍沙箱规则**（纵深防御）——
即使宿主把 `ToolSandbox` 换成了 `permissive`，工具本身依然守得住。

HTTP 端点同样默认关闭（`llm.web.enabled=false`）：一个能触发模型调用、
并可能间接驱动本地工具执行的入口，不应该因为"引了依赖"就自动对外。

---

## 十、扩展指南

**加一个协议** → 实现 `ProtocolCodec`，注册为 bean。调用方代码零改动。

**加一个 Loop** → 继承 `AbstractAgentLoop`，打 `@LlmLoop("名字")`。
`ctx` 已经把模型调用、工具执行、沙箱、审批、流式聚合、用量统计都准备好了。

**加一个工作流（不写 Java）** → 写一份 YAML，配 `llm.workflows.<名字>.location`，
就会被注册成 `workflow:<名字>`，`@LlmAgent(loop = "workflow:<名字>")` 即可用。
定义有问题会在**启动期**报错，而不是跑到一半才发现图是错的。

**加一种工作流节点类型** → 在 `NodeType` 加枚举值，在 `WorkflowEngine.dispatch` 加一个分支，
在 `WorkflowNode.validate` 加该类型的必填校验。三处都很小，但请先想清楚它是否真该是个节点 ——
把逻辑下沉到工具里通常更好。

**加一个工具** → 在任意 Spring bean 的方法上打 `@LlmTool`；
或者实现 `ToolCallback` 注册为 bean；或者用 `LlmToolCatalog.register(...)` 动态登记。

**换一个模型** → 改 yml 两行；或定义 `LlmModel` bean；
或写一个 `LlmModel` 装饰器（`RetryingLlmModel` 就是范例）。

**接入真实 tokenizer** → 实现 `TokenEstimator`。

---

## 十一、已知取舍

| 取舍 | 原因 | 代价 |
|---|---|---|
| 内置工具默认关闭 | 误开的爆炸半径等于交出宿主机 | 多写两行配置 |
| HTTP 端点默认关闭 | 无鉴权的模型入口风险过高 | 多写一行配置 |
| `reasoning_content` 回灌默认关闭 | `deepseek-reasoner` 禁止输入携带该字段（会 400） | 需要时用 `extra("echo_reasoning", true)` 打开 |
| 并行工具调用按声明顺序记账 | 完成顺序不确定，审计轨迹必须可复现 | 需要等最慢的那个 |
| 工具异常一律转成错误结果 | 让模型有机会自我纠正 | 真正的框架级故障也被降级，需靠监听器兜底 |
| 声明式接口不支持 default 方法 | JDK 动态代理转发 default 方法需要额外 InvocationHandler 支持 | 显式报错而不是静默失效 |
| `@LlmTool` 方法需 `-parameters` 编译 | 否则参数名退化为 `arg0` | 已显式配置 `maven-compiler-plugin` |
| 工作流没有并行节点 | `LoopContext` 的历史与记账不是线程安全的，共用它并发跑分支会引入数据竞争 | 想要并行就把并发放进工具里，图的语义保持"单线程可复现" |
| 边条件只支持单层比较 + and/or/not | 图省事写成的复杂表达式没人愿意维护，逻辑应当下沉到工具 | 需要复杂判断时用 `set` + 工具预计算出一个布尔变量 |
| `matches` 的右操作数按原文截取 | 正则本身就是一门语言，先分词再拼回去会拆坏 `(?i)` 这类写法 | 与 `and` / `or` / `)` 相邻时必须加引号 |
| 节点 id 不能叫 `input` / `last` / `error` / `current` | 同名会遮蔽内置变量，是最难排查的一类 bug | 加载期直接报错并提示改名 |
| YAML 入口需要 SnakeYAML（core 里声明为 optional） | core 不想为可选入口背一个必装依赖 | 无 Spring 的裸 core 场景改用 JSON，或自行引入 snakeyaml |

---

## 十二、测试策略

| 层次 | 手段 | 验证什么 |
|---|---|---|
| 协议 | 固定 JSON / SSE fixture | 编解码往返、分片拼装、边界容错 |
| 工具 | 真实反射 + 真实文件系统 | Schema 生成、参数绑定、沙箱拦截 |
| Loop | `ScriptedModel`（按脚本吐响应） | 步数、工具序列、文本协议降级 |
| 工作流引擎 | 假 `WorkflowRuntime`（按节点 id 编排返回值与失败次数） | 分支选择、循环退出、重试、`continueOnError`、预算中断、两道护栏 |
| Agent | 假模型 + 假沙箱 + 假审批 | 工具闭环、拦截器、记忆、超时、并行顺序 |
| 集成 | `ApplicationContextRunner` | 条件装配、用户 bean 覆盖、开关语义 |
| 端到端 | `@SpringBootTest` + `@LlmAgent` 接口 | 注解 → 代理 → 运行时 → 模型 全链路 |

全部测试**不联网、不花钱**，可在 CI 与离线环境稳定运行。

---

## 十三、目录导航

| 想了解 | 看这里 |
|---|---|
| 统一消息模型 | `core/message/`, `core/chat/` |
| 三协议实现 | `core/protocol/{openai,anthropic,gemini}/` |
| 模型与传输 | `core/model/`, `core/transport/` |
| 工具与沙箱 | `core/tool/`, `core/sandbox/` |
| 内置工具 | `core/tool/builtin/` |
| Agent 循环 | `core/loop/` |
| 工作流引擎（领域模型 / 引擎 / 条件表达式 / 读写） | `core/workflow/` |
| 工作流的 Agent 适配层与两条路线 | `core/loop/WorkflowLoop.java`, `core/loop/StagedLoop.java`, `core/loop/LoopContextRuntime.java` |
| 运行时引擎 | `core/agent/DefaultAgent.java`, `core/loop/DefaultLoopContext.java` |
| 提示词模板 | `core/prompt/PromptTemplates.java` |
| Spring 集成 | `spring/LlmAutoConfiguration.java`, `spring/LlmAgentRegistrar.java` |
| 可运行示例 | `benxin-examples/` |
