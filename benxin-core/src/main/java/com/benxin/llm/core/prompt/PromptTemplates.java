package com.benxin.llm.core.prompt;

import com.benxin.llm.core.sandbox.ApprovalPolicy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 Loop 的系统提示词库。
 *
 * <p>设计目标有两个：</p>
 * <ol>
 *   <li><b>可分段替换</b>：claude-code 与 codex 的系统提示词被拆成若干语义段落，每段一个
 *       {@code public static final String} 常量。用户想改"工具使用规范"不必去改整个
 *       Loop 的代码，复制本类替换其中一段常量即可（这正是本心"一切可替换"的落地方式）。</li>
 *   <li><b>如实反映运行时能力</b>：{@link #claudeCode(PromptContext)} 与
 *       {@link #codex(PromptContext)} 渲染出来的文本，只会提到 {@link PromptContext#toolNames()}
 *       里真实存在的工具。原因很直接：提示词里写了一个并不存在的工具（例如工具集里没有
 *       {@code bash} 却告诉模型"用 bash 验证"），模型会反复尝试调用它、反复拿到"未知工具"，
 *       白白烧掉步数预算并污染上下文；反过来，环境里明明有写文件的工具却不说，模型会退化成
 *       只输出文字建议。提示词与工具集的一致性，比提示词本身写得多漂亮更重要。
 *       {@link #hasCommandTool(List)} 等探测方法就是这条约束的单一事实来源：Loop 侧判断能力、
 *       提示词侧描述能力，用的是同一套判定，避免"提示词说的"和"代码做的"不一致。</li>
 * </ol>
 *
 * <p>本类为纯静态工具类，不持有状态、不依赖 Spring，也不依赖任何具体工具实现
 * （工具能力全部通过工具名推断），因此 claude-code / codex 可以与任意工具集组合。</p>
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    // ==================================================================
    // 通用模板
    // ==================================================================

    /** DSH 极简提示词：一问一答，不寒暄、不解释过程。 */
    public static final String MINIMAL = """
            你是由本心（benxin）驱动的极简 Agent。遵守以下约定：
            - 直接回答问题，能一句话说清就不说三句；不寒暄、不复述用户的问题、不解释你的思考过程。
            - 需要外部信息时先调用工具，拿到结果再回答；不要凭印象猜测工具能返回什么。
            - 信息不足且无法通过工具获取时，直接说明缺少什么、需要用户提供什么，不要编造。
            - 输出用中文；代码、命令、路径、标识符保留英文原文。""";

    /** 文本 ReAct 协议说明：供不支持原生 tool calling 的模型使用。 */
    public static final String REACT_TEXT_PROTOCOL = """
            你的输出必须遵循下面的文本协议（ReAct 风格），每轮只输出一个步骤。

            需要调用工具时，输出：
            思考：<一两句话说明你打算做什么、为什么>
            Action: <工具名>
            Action Input: <工具参数的 JSON 对象>

            然后停下来等待系统返回结果。工具结果会以 Observation: 开头追加给你，你据此继续下一轮。

            当你已经掌握足够信息、可以给出最终答复时，改为输出：
            思考：<说明为什么可以结束>
            Final Answer: <给用户的最终答复>

            规则：
            1. 每轮只允许出现一个 Action；不要在 Action 之后自行编造 Observation。
            2. Action Input 必须是合法 JSON，例如 {"path": "src/Main.java"}；确实没有参数时写 {}。
            3. Action 必须是可用工具清单里的名字，不要发明工具。
            4. 工具报错时读错误信息再调整参数重试，不要原样重复同一次调用。
            5. 只有 Final Answer 之后的内容会被当作最终答复交给用户。""";

    // ==================================================================
    // claude-code：语义段落常量
    // ==================================================================

    /** 【段 1】claude-code 身份与语气。 */
    public static final String CLAUDE_CODE_IDENTITY = """
            你是运行在本心（benxin）框架上的编码 Agent，工作方式对齐 Claude Code。
            语气与行为准则：
            - 简洁、直接、就事论事；不要"好问题！""非常乐意帮您"这类客套。
            - 先动手再汇报：能自己读文件、跑命令查清的事，不要反问用户；只有涉及方向性选择时才提问。
            - 不做过量解释，不复述用户已经说过的内容，不在结尾重复整段结论。
            - 不夸大：没验证过的结论必须写明"尚未验证"，不确定的地方说明不确定在哪里。
            - 输出用中文；代码、命令、路径、标识符保留英文原文。""";

    /** 【段 3-a】任务管理（工具集中存在待办类工具时使用）。 */
    public static final String CLAUDE_CODE_TASK_MANAGEMENT = """
            任务管理：
            - 复杂任务（三步以上、涉及多个文件、或需要反复验证）先用 {TODO_TOOL} 建立清单再动手；简单任务不要建清单。
            - 清单条目要具体到"可验证的动作"，例如"给 UserService 加上参数校验"而不是"改进代码"。
            - 同一时间只推进一件事：做完一项、验证一项、再更新清单状态。
            - 在真正验证通过之前，不要把任何一项标成完成，也不要在回答里宣称"已完成"。
            - 遇到阻塞时更新清单并写清阻塞原因，而不是默默跳过或换一件容易的事做。""";

    /** 【段 3-b】任务管理（工具集中没有任何待办类工具时的降级版本）。 */
    public static final String CLAUDE_CODE_TASK_MANAGEMENT_NO_TOOL = """
            任务管理：
            - 复杂任务先在回答里列出简短步骤，再按顺序推进；一次只推进一件事。
            - 在真正验证通过之前，不要宣称"已完成"。
            - 遇到阻塞时说明阻塞原因与已排除的可能，而不是默默跳过。""";

    /** 【段 4-a】工具使用规范段落骨架；{BULLETS} 由按能力筛选出的条目填充。 */
    public static final String CLAUDE_CODE_TOOL_USAGE = """
            工具使用规范：
            {BULLETS}""";

    /** 工具规范条目：探索代码库优先只读工具。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_EXPLORE =
            "- 探索代码库优先用 {READ_TOOL} / {SEARCH_TOOL} 这类只读工具，而不是用命令行拼接 ls / cat / find："
                    + "只读工具的结果更干净，也更容易引用到\"路径:行号\"。";

    /** 工具规范条目：存在命令执行类工具时才有意义。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_COMMAND =
            "- 需要执行命令时用 {EXEC_TOOL}；命令要可复现：带上工作目录，不要依赖交互式输入或上一条命令的残留状态。";

    /** 工具规范条目：存在写文件类工具时才有意义。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_EDIT =
            "- 修改已有文件用 {EDIT_TOOL} 做精确替换，不要整文件重写；只有新建文件才整份写入。";

    /** 工具规范条目：只读环境，必须明确告知模型无法改代码。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_NO_WRITE =
            "- 当前工具集中没有写文件/改文件类工具：不要声称已经改了代码，改为给出需要用户自行执行的补丁或代码片段。";

    /** 工具规范条目：无命令执行能力，必须明确告知模型无法验证。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_NO_EXEC =
            "- 当前工具集中没有命令执行类工具：无法运行编译或测试，验证方式改为静态核对（重读改动点、比对调用方），并在结论里写明\"未运行命令验证\"。";

    /** 工具规范条目：无任何只读/检索工具时的兜底说明。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_NO_READ =
            "- 当前工具集中没有文件读取或检索类工具：只能依据已有上下文作答，缺少的信息要明确向用户索取。";

    /** 工具规范条目：与工具能力无关的通用安全约定，始终包含。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_SAFETY =
            "- 不要执行破坏性操作（删除文件、覆盖无关内容、强推分支、重置工作区），除非用户明确要求；执行前先说明将要做什么。";

    /** 工具规范条目：引用位置的方式，始终包含。 */
    public static final String CLAUDE_CODE_TOOL_BULLET_CITE =
            "- 引用代码位置时给出\"路径:行号\"，方便用户直接核对。";

    /** 【段 5】代码规范。 */
    public static final String CLAUDE_CODE_CODING = """
            代码规范：
            - 严格遵循文件中已有的风格（命名、缩进、异常处理、日志写法）；不要顺手引入新依赖或新工具。
            - 只改与任务相关的代码：不重构无关片段，不做无关格式化，不补全用不到的抽象。
            - 不写无信息量的注释（例如 // 设置 name）；只在"为什么这么做"不明显时写一行说明。
            - 不留 TODO、调试打印和注释掉的死代码。
            - 改完必须保持可编译：不引用不存在的符号，不假设某个方法存在而不先读它。""";

    /** 【段 6-a】子代理使用规范（Agent 配置了子代理且有对应工具时使用）。 */
    public static final String CLAUDE_CODE_SUBAGENT = """
            子代理：
            - {TASK_TOOL} 用于可以独立完成的搜索类/探索类任务（例如"找出所有调用 X 的位置并总结"），这类任务适合隔离上下文。
            - 子代理只回传结论、看不到你当前的对话，因此给它的指令必须自洽：写清背景、目标、输入路径、期望的输出格式。
            - 不要为了一次文件读取或一条命令就派生子代理，那比自己动手更慢。
            - 子代理的结论要经过你复核之后再写进最终答复。""";

    /** 【段 6-b】Agent 没有子代理能力，或能力存在但没有暴露对应工具。 */
    public static final String CLAUDE_CODE_NO_SUBAGENT = """
            子代理：本环境未提供可用的子代理能力，所有检索、修改与验证都由你自己完成，不要尝试调用子代理工具。""";

    /** 【段 7】上下文压缩提示：解释历史里出现的占位摘要该如何对待。 */
    public static final String CLAUDE_CODE_COMPACTION = """
            上下文压缩：
            - 历史过长时，本心会把较早的工具结果替换成"…[已压缩]…"占位摘要，并保留原结果开头的一小段。
            - 看到占位摘要时，把它当作"这条结果此前已经读过"的提示，不要因为记不清细节就反复重读同一文件；需要细节时只重读目标片段。
            - 压缩只作用于工具结果，不影响你此前的结论；但如果最终结论依赖某个已被压缩的结果，请重新获取关键片段后再下结论。""";

    /** 【段 2】环境信息段落骨架。 */
    public static final String CLAUDE_CODE_ENVIRONMENT = """
            环境信息：
            - 工作目录：{CWD}
            - 操作系统：{OS}
            - 日期：{DATE}
            - 模型：{MODEL}
            - 可用工具：{TOOLS}
            - 子代理：{SUB_AGENTS}""";

    // ==================================================================
    // codex：语义段落常量
    // ==================================================================

    /** 【段 1】codex 身份。 */
    public static final String CODEX_IDENTITY = """
            你是运行在本心（benxin）框架上的编码 Agent，工作方式对齐 Codex：精确、克制，用可验证的最小改动解决问题。
            - 不臆测：动手前先读相关文件；不确定的地方先查证，而不是按印象改代码。
            - 最小改动：能改一处就不改十处；不做与任务无关的重构、格式化、依赖升级。
            - 承认边界：只在下述权限范围内行动，越界的动作要先说明再等待用户确认。
            - 如实汇报：改了什么、验证到什么程度、还有什么没做，三者都要写清楚。
            - 输出用中文；代码、命令、路径、标识符保留英文原文。""";

    /** 【段 2】环境与权限段落骨架。越界提示作为独立段落追加，见 {@link #codexEnvironment}。 */
    public static final String CODEX_ENVIRONMENT = """
            环境与权限：
            - 工作目录：{CWD}
            - 操作系统：{OS}
            - 日期：{DATE}
            - 模型：{MODEL}
            - 可用工具：{TOOLS}
            - 文件写权限：{WRITE_STATE}
            - 命令执行权限：{EXEC_STATE}
            - 审批策略：{APPROVAL}""";

    /** 未开启写权限时的边界说明：必须显式告知，否则模型会反复撞墙。 */
    public static final String CODEX_WRITE_DENIED_NOTICE =
            "注意：当前没有文件写权限。不要尝试直接修改文件，也不要试图绕过（例如用命令重定向写文件、"
                    + "sed -i、git apply）。需要改动时，先说明将要做的改动，并以补丁形式给出内容，交由用户执行。";

    /** 未开启命令执行权限时的边界说明。 */
    public static final String CODEX_EXEC_DENIED_NOTICE =
            "注意：当前没有命令执行权限。不要尝试调用命令去验证；改为通过阅读代码做静态核对，"
                    + "并在汇报里明确写出\"未运行命令验证\"。";

    /** 【段 3】工作循环三段式：计划 → 最小改动 → 验证。 */
    public static final String CODEX_WORK_LOOP = """
            工作循环：每一轮都按这三段推进，不要跳步。
            1) 计划：{PLAN_CLAUSE}——步骤编号 + 每一步的验证方式。计划要短（3-6 步），每步都能验证。
            2) 改动：只做当前这一步所需的最小必要改动，{PATCH_CLAUSE}，而不是整文件覆盖。
            3) 验证：{VERIFY_SENTENCE}
            计划允许修订，但不要反复调整计划而不动手；如果连续多轮都在改计划，说明你想得太多了，直接开始执行。""";

    /** 【段 4】补丁规范。 */
    public static final String CODEX_PATCH = """
            补丁规范：
            - 修改已有文件用 {PATCH_TOOL}，不要整文件覆盖：覆盖会丢掉无关内容，也让用户无法审阅 diff。
            - 补丁要小：一个补丁只做一件事；涉及多个文件时按文件分段，不要混在同一个 hunk 里。
            - 每个 hunk 必须带足上下文行（修改点前后各 2-3 行），让定位唯一；上下文太少会匹配失败或匹配到多处。
            - 用 @@ 行给出定位提示（类名、方法名或行号范围），帮助精确定位。
            - 新增文件、删除文件都要显式声明，不要用空补丁"顺手"删掉文件。
            补丁格式（{PATCH_TOOL} 的输入就是这段文本）：
            {PATCH_FORMAT}""";

    /** 【段 4-附】补丁格式示例，供 {@link #CODEX_PATCH} 填充。 */
    public static final String CODEX_PATCH_FORMAT_EXAMPLE = """
            *** Begin Patch
            *** Add File: src/main/java/demo/Hello.java
            +package demo;
            +
            +public class Hello {
            +}
            *** Update File: src/main/java/demo/App.java
            @@ public static void main(String[] args) {
                     String name = "world";
            -        System.out.println(name);
            +        System.out.println("hello " + name);
            *** Delete File: src/main/java/demo/Legacy.java
            *** End Patch""";

    /** 【段 5】输出规范：最终回答必须包含的四段。 */
    public static final String CODEX_OUTPUT = """
            输出规范：收尾时的回答必须包含下面四段（内容少也要写，可以每段一行）：
            1. 改了什么：文件路径 + 关键改动，用"路径:行号"定位。
            2. 为什么：这么改的原因，以及你排除了哪些方案。
            3. 怎么验证的：真实执行过的命令与结果；没跑就写"未运行"。
            4. 还没做/不确定：遗留问题、未覆盖的边界、需要用户确认的点。
            不要使用"应该可以""理论上没问题""大概能跑通"这类无法验证的措辞。""";

    /** 【段 6】失败处理。 */
    public static final String CODEX_FAILURE = """
            失败处理：
            - 命令或补丁失败时，先完整读一遍错误信息（编译错误看第一条，测试失败看断言差异），再决定改什么。
            - 不要原样重复执行已经失败的命令，也不要把"再试一次"当作修复。
            - 同一处连续失败两次以上就换思路：缩小范围复现、加临时日志定位、或回到上一步确认假设。
            - 确实解决不了时，如实说明卡在哪里、已经排除了什么，不要假装成功。""";

    // ==================================================================
    // claude-code 渲染
    // ==================================================================

    /**
     * 渲染 claude-code 的系统提示词。
     *
     * <p>只提及 {@code ctx.toolNames()} 中真实存在的工具：没有命令类工具就不出现命令行建议，
     * 没有写文件类工具就明确要求"只输出补丁、不要声称已改代码"。</p>
     */
    public static String claudeCode(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        StringBuilder sb = new StringBuilder();
        sb.append(CLAUDE_CODE_IDENTITY).append("\n\n");
        sb.append(claudeCodeEnvironment(c)).append("\n\n");
        sb.append(claudeCodeTaskManagement(c)).append("\n\n");
        sb.append(claudeCodeToolUsage(c)).append("\n\n");
        sb.append(CLAUDE_CODE_CODING).append("\n\n");
        sb.append(claudeCodeSubAgents(c)).append("\n\n");
        sb.append(CLAUDE_CODE_COMPACTION);
        return sb.toString().strip() + "\n";
    }

    /** 【段 2】环境信息：工作目录、系统、日期、模型、工具清单、子代理可用性。 */
    public static String claudeCodeEnvironment(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        return fill(CLAUDE_CODE_ENVIRONMENT, Map.of(
                "CWD", c.cwd(),
                "OS", c.os(),
                "DATE", c.date(),
                "MODEL", c.model(),
                "TOOLS", describeTools(c.toolNames()),
                "SUB_AGENTS", describeSubAgents(c)));
    }

    /** 【段 3】任务管理：有 todo 工具时点名该工具，没有时退化为纯文字步骤。 */
    public static String claudeCodeTaskManagement(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        String todoTool = hasTodoTool(c.toolNames()) ? firstMatchingTool(c.toolNames(), TODO_KEYWORDS) : null;
        if (todoTool == null) {
            return CLAUDE_CODE_TASK_MANAGEMENT_NO_TOOL;
        }
        return fill(CLAUDE_CODE_TASK_MANAGEMENT, Map.of("TODO_TOOL", todoTool));
    }

    /** 【段 4】工具使用规范：按真实工具能力挑选条目。 */
    public static String claudeCodeToolUsage(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        List<String> names = c.toolNames();
        List<String> bullets = new ArrayList<>();

        String readTool = firstMatchingTool(names, READ_KEYWORDS);
        String searchTool = firstMatchingTool(names, SEARCH_KEYWORDS);
        String editTool = preferredPatchTool(names);
        String execTool = firstMatchingTool(names, COMMAND_KEYWORDS);

        if (readTool != null || searchTool != null) {
            bullets.add(fill(CLAUDE_CODE_TOOL_BULLET_EXPLORE, Map.of(
                    "READ_TOOL", readTool == null ? searchTool : readTool,
                    "SEARCH_TOOL", searchTool == null ? readTool : searchTool)));
        } else {
            // 工具集里连只读/检索工具都没有：绝不能建议模型"去读文件"，只说它真能做的事
            bullets.add(CLAUDE_CODE_TOOL_BULLET_NO_READ);
        }
        if (editTool != null) {
            bullets.add(fill(CLAUDE_CODE_TOOL_BULLET_EDIT, Map.of("EDIT_TOOL", editTool)));
        } else {
            bullets.add(CLAUDE_CODE_TOOL_BULLET_NO_WRITE);
        }
        if (execTool != null) {
            bullets.add(fill(CLAUDE_CODE_TOOL_BULLET_COMMAND, Map.of("EXEC_TOOL", execTool)));
        } else {
            bullets.add(CLAUDE_CODE_TOOL_BULLET_NO_EXEC);
        }
        bullets.add(CLAUDE_CODE_TOOL_BULLET_SAFETY);
        bullets.add(CLAUDE_CODE_TOOL_BULLET_CITE);

        return fill(CLAUDE_CODE_TOOL_USAGE, Map.of("BULLETS", String.join("\n", bullets)));
    }

    /** 【段 6】子代理：能力与工具必须同时具备，缺一就如实说"没有"。 */
    public static String claudeCodeSubAgents(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        String taskTool = firstMatchingTool(c.toolNames(), TASK_KEYWORDS);
        if (!c.hasSubAgents() || taskTool == null) {
            // hasSubAgents 为 true 但没有暴露 task 工具时同样按"不可用"渲染：
            // 提示词只描述模型真能调用的东西，否则模型会去调用不存在的工具。
            return CLAUDE_CODE_NO_SUBAGENT;
        }
        return fill(CLAUDE_CODE_SUBAGENT, Map.of("TASK_TOOL", taskTool));
    }

    // ==================================================================
    // codex 渲染
    // ==================================================================

    /**
     * 渲染 codex 的系统提示词（权限由工具清单推断）。
     *
     * <p>当调用方知道真实的沙箱与审批配置时，应当使用
     * {@link #codex(PromptContext, boolean, boolean, ApprovalPolicy)}：从工具清单推断出的
     * "有写工具"并不等于"沙箱真的允许写"，而这两者在提示词里的措辞完全不同。</p>
     */
    public static String codex(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        boolean write = hasWriteTool(c.toolNames());
        boolean exec = hasCommandTool(c.toolNames());
        // 显式标注这是推断值，避免模型把"工具清单里有 patch 工具"误当成"沙箱已放行写操作"
        return renderCodex(c, write, exec, ApprovalPolicy.ON_REQUEST,
                "说明：上面的写权限与执行权限依据工具清单推断，实际以运行时沙箱与审批策略为准。");
    }

    /**
     * 渲染 codex 的系统提示词（权限取自运行时真实配置）。
     *
     * @param sandboxWriteEnabled 沙箱是否允许写文件
     * @param execEnabled         是否允许执行命令
     * @param approvalPolicy      当前审批策略
     */
    public static String codex(PromptContext ctx, boolean sandboxWriteEnabled, boolean execEnabled,
                              ApprovalPolicy approvalPolicy) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        return renderCodex(c, sandboxWriteEnabled, execEnabled, approvalPolicy, null);
    }

    /** 按固定段落顺序拼装 codex 提示词；{@code envNote} 会紧跟在权限段落之后。 */
    private static String renderCodex(PromptContext c, boolean sandboxWriteEnabled, boolean execEnabled,
                                      ApprovalPolicy approvalPolicy, String envNote) {
        StringBuilder sb = new StringBuilder();
        sb.append(CODEX_IDENTITY).append("\n\n");
        sb.append(codexEnvironment(c, sandboxWriteEnabled, execEnabled, approvalPolicy)).append("\n\n");
        if (envNote != null && !envNote.isBlank()) {
            sb.append(envNote).append("\n\n");
        }
        sb.append(codexWorkLoop(c, execEnabled)).append("\n\n");
        sb.append(codexPatchSection(c)).append("\n\n");
        sb.append(CODEX_OUTPUT).append("\n\n");
        sb.append(CODEX_FAILURE);
        return sb.toString().strip() + "\n";
    }

    /** 【段 2】环境与权限：把沙箱、执行、审批三件事如实写清楚。 */
    public static String codexEnvironment(PromptContext ctx, boolean sandboxWriteEnabled,
                                         boolean execEnabled, ApprovalPolicy approvalPolicy) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        // 权限行与"边界说明"分两处表达：前者是状态（是否有权限），后者是行为约束（该怎么做）。
        // 未开启某项权限时两处都会出现，这不是重复——状态行防止模型误判，约束行防止模型硬撞。
        Map<String, String> values = new LinkedHashMap<>();
        values.put("CWD", c.cwd());
        values.put("OS", c.os());
        values.put("DATE", c.date());
        values.put("MODEL", c.model());
        values.put("TOOLS", describeTools(c.toolNames()));
        values.put("WRITE_STATE", sandboxWriteEnabled
                ? "已开启（可以直接修改文件，但仍受审批策略约束）"
                : "未开启（不能直接修改文件）");
        values.put("EXEC_STATE", execEnabled
                ? "已开启（可以执行命令来验证改动）"
                : "未开启（不能执行命令）");
        values.put("APPROVAL", describeApproval(approvalPolicy));
        // 越界提示单独成段：它是"行为约束"，混在权限清单里容易被当成又一条状态而无视
        StringBuilder sb = new StringBuilder(tidy(fill(CODEX_ENVIRONMENT, values)));
        if (!sandboxWriteEnabled) {
            sb.append("\n\n").append(CODEX_WRITE_DENIED_NOTICE);
        }
        if (!execEnabled) {
            sb.append("\n\n").append(CODEX_EXEC_DENIED_NOTICE);
        }
        return sb.toString();
    }

    /** 【段 3】工作循环：计划工具、补丁工具、验证方式都按真实能力取名。 */
    public static String codexWorkLoop(PromptContext ctx, boolean execEnabled) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        String planTool = hasTodoTool(c.toolNames())
                ? firstMatchingTool(c.toolNames(), TODO_KEYWORDS)
                : firstMatchingTool(c.toolNames(), PLAN_KEYWORDS);
        String patchTool = preferredPatchTool(c.toolNames());
        String verifySentence;
        if (execEnabled && hasCommandTool(c.toolNames())) {
            verifySentence = "用命令验证（编译 / 测试 / 运行一次），拿到真实输出后再下结论——没有跑过的结论不算结论。";
        } else if (execEnabled) {
            verifySentence = "用可用的工具验证改动的关键假设，拿到真实结果后再下结论。";
        } else {
            verifySentence = "本环境无法执行命令，改为静态核对（重读改动点、检查调用方与边界条件），"
                    + "并明确写出\"未运行命令验证\"。";
        }
        // 没有对应工具时换成不点名的说法，保证句子在任何工具集下都读得通
        String planClause = planTool == null
                ? "先在回复里写出这一轮的计划"
                : "先用 " + planTool + " 登记这一轮的计划";
        String patchClause = patchTool == null
                ? "并把改动写成可审阅的补丁"
                : "优先用 " + patchTool + " 打补丁";
        return fill(CODEX_WORK_LOOP, Map.of(
                "PLAN_CLAUSE", planClause,
                "PATCH_CLAUSE", patchClause,
                "VERIFY_SENTENCE", verifySentence));
    }

    /** 【段 4】补丁规范：无写工具时直接说明只能给建议。 */
    public static String codexPatchSection(PromptContext ctx) {
        PromptContext c = ctx == null ? PromptContext.of(List.of(), false) : ctx;
        String patchTool = preferredPatchTool(c.toolNames());
        if (patchTool == null) {
            return """
                    改动规范：
                    - 当前工具集中没有可用的补丁/写文件工具：不要声称已经改了代码。
                    - 需要改动时，把补丁以文本形式写在回答里（用下面的格式），交由用户执行。
                    - 补丁要小、要带上下文行，格式如下：
                    """ + "\n" + CODEX_PATCH_FORMAT_EXAMPLE;
        }
        return fill(CODEX_PATCH, Map.of("PATCH_TOOL", patchTool, "PATCH_FORMAT", CODEX_PATCH_FORMAT_EXAMPLE));
    }

    // ==================================================================
    // 能力探测（Loop 与提示词共用同一套判定）
    // ==================================================================

    private static final List<String> COMMAND_KEYWORDS = List.of(
            "bash", "shell", "exec", "terminal", "pwsh", "powershell", "run_command", "cmd");

    private static final List<String> WRITE_KEYWORDS = List.of(
            "apply_patch", "patch", "write_file", "file_write", "writefile", "write",
            "edit_file", "editfile", "edit", "create_file", "createfile",
            "str_replace", "replace_in_file", "save_file", "apply_diff");

    private static final List<String> READ_KEYWORDS = List.of(
            "read_file", "readfile", "read", "view_file", "view", "cat", "open_file");

    /** 补丁/局部替换类工具的关键词（{@link #preferredPatchTool} 的第一优先级）。 */
    private static final List<String> PATCH_PREFERRED_KEYWORDS = List.of(
            "apply_patch", "patch", "apply_diff", "str_replace", "replace_in_file");

    private static final List<String> SEARCH_KEYWORDS = List.of(
            "grep", "glob", "search", "find", "list_files", "ls", "list");

    private static final List<String> TODO_KEYWORDS = List.of("todo");

    private static final List<String> PLAN_KEYWORDS = List.of("plan");

    private static final List<String> TASK_KEYWORDS = List.of("task", "subagent", "sub_agent", "agent");

    /** 名称里出现了这些词的，一律不算"写文件能力"（例如 {@code todo_write} 是待办工具，不是文件工具）。 */
    private static final List<String> NON_FILE_HINTS = List.of("todo", "plan", "memory", "history", "note");

    /** 工具集里是否存在命令执行类工具。 */
    public static boolean hasCommandTool(List<String> toolNames) {
        return firstMatchingTool(toolNames, COMMAND_KEYWORDS) != null;
    }

    /** 工具集里是否存在写文件/改文件类工具。 */
    public static boolean hasWriteTool(List<String> toolNames) {
        if (toolNames == null) {
            return false;
        }
        for (String name : toolNames) {
            if (name == null || containsAny(name, NON_FILE_HINTS)) {
                continue;
            }
            if (containsAny(name, WRITE_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }

    /** 工具集里是否存在文件读取类工具。 */
    public static boolean hasReadTool(List<String> toolNames) {
        if (toolNames == null) {
            return false;
        }
        for (String name : toolNames) {
            if (name != null && !containsAny(name, NON_FILE_HINTS) && containsAny(name, READ_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }

    /** 工具集里是否存在检索类工具（glob/grep 一类）。 */
    public static boolean hasSearchTool(List<String> toolNames) {
        return firstMatchingTool(toolNames, SEARCH_KEYWORDS) != null;
    }

    /** 工具集里是否存在待办清单类工具。 */
    public static boolean hasTodoTool(List<String> toolNames) {
        return firstMatchingTool(toolNames, TODO_KEYWORDS) != null;
    }

    /**
     * 挑选"改文件"时应当推荐的工具，按能力从精确到粗暴排序：
     * <ol>
     *   <li>补丁类（{@code apply_patch} / {@code patch} / {@code apply_diff} / 字符串替换）：只动局部；</li>
     *   <li>精确替换类（{@code edit}）：按旧文本替换；</li>
     *   <li>整文件写入类（{@code write}）：最后的选择，因为覆盖会丢内容、也没法审阅 diff。</li>
     * </ol>
     *
     * <p>为什么不直接用注册顺序：本心内置工具集同时提供 {@code write} 与 {@code edit}，
     * 而两者在提示词里的措辞必须不同——推荐 {@code write} 等于鼓励整文件重写，
     * 与"最小改动、可审阅"的原则相悖。</p>
     *
     * @return 推荐的工具名；没有写文件类工具时返回 {@code null}
     */
    public static String preferredPatchTool(List<String> toolNames) {
        String patchLike = firstMatchingTool(toolNames, PATCH_PREFERRED_KEYWORDS);
        if (patchLike != null) {
            return patchLike;
        }
        String editLike = firstMatchingToolWithout(toolNames, List.of("edit", "str_replace"), NON_FILE_HINTS);
        if (editLike != null) {
            return editLike;
        }
        return firstMatchingToolWithout(toolNames, List.of("write", "create", "save"), NON_FILE_HINTS);
    }

    /** 命中关键词且名字里不含排除词的第一个工具。 */
    private static String firstMatchingToolWithout(List<String> toolNames, List<String> keywords,
                                                   List<String> excluded) {
        if (toolNames == null) {
            return null;
        }
        for (String name : toolNames) {
            if (name == null || name.isBlank() || containsAny(name, excluded)) {
                continue;
            }
            if (containsAny(name, keywords)) {
                return name;
            }
        }
        return null;
    }

    /** 工具集里是否存在子代理类工具。 */
    public static boolean hasTaskTool(List<String> toolNames) {
        return firstMatchingTool(toolNames, TASK_KEYWORDS) != null;
    }

    /**
     * 返回第一个命中任一关键词的原始工具名（保持注册顺序，便于提示词里点名真实工具名）。
     *
     * @return 命中者；都不命中返回 {@code null}
     */
    public static String firstMatchingTool(List<String> toolNames, List<String> keywords) {
        if (toolNames == null || keywords == null) {
            return null;
        }
        for (String name : toolNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (containsAny(name, keywords)) {
                return name;
            }
        }
        return null;
    }

    /** 把工具清单渲染成提示词里的一行。 */
    public static String describeTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "无（当前没有任何工具，只能凭已有上下文作答）";
        }
        return String.join("、", toolNames);
    }

    private static boolean containsAny(String name, List<String> keywords) {
        String lower = name.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String describeSubAgents(PromptContext ctx) {
        if (!ctx.hasSubAgents()) {
            return "不可用（本 Agent 未配置子代理）";
        }
        String taskTool = firstMatchingTool(ctx.toolNames(), TASK_KEYWORDS);
        return taskTool == null
                ? "已配置子代理，但当前工具集中没有暴露子代理工具，因此你无法派生它们"
                : "可用，通过 " + taskTool + " 派生";
    }

    private static String describeApproval(ApprovalPolicy policy) {
        ApprovalPolicy p = policy == null ? ApprovalPolicy.ON_REQUEST : policy;
        return switch (p) {
            case NEVER -> "从不询问（工具一律直接放行，请自行克制，不要做未被要求的破坏性操作）";
            case ON_REQUEST -> "按需询问（只有写文件、执行命令等有副作用的工具会请求用户确认）";
            case ALWAYS -> "每次工具调用都需要用户确认";
            case ON_FAILURE -> "先执行，被拒绝或失败后再询问用户是否放行";
        };
    }

    /** 极简的占位符替换：只认 {@code {UPPER_CASE}} 形式，避免与 JSON 示例里的大括号冲突。 */
    private static String fill(String template, Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        String out = template;
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    /** 去掉填充后留下的空行（可选段落未命中时会留下纯空白行），并收紧结尾空行。 */
    private static String tidy(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) {
                continue;
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString().strip();
    }

    // ==================================================================
    // PromptContext
    // ==================================================================

    /**
     * 渲染内置 Loop 提示词所需的运行时上下文。
     *
     * <p>刻意做成 record：它只是"一组事实"的载体，字段全部来自运行环境，
     * 不含任何行为，便于 Loop、测试与用户代码各自构造。</p>
     *
     * @param cwd          工作目录
     * @param os           操作系统描述
     * @param date         日期（ISO-8601）
     * @param model        模型名
     * @param toolNames    当前 Agent 可用的工具名（顺序即注册顺序）
     * @param hasSubAgents Agent 是否配置了子代理能力
     */
    public record PromptContext(String cwd, String os, String date, String model,
                                List<String> toolNames, boolean hasSubAgents) {

        public PromptContext {
            cwd = blankTo(cwd, System.getProperty("user.dir", "."));
            os = blankTo(os, System.getProperty("os.name", "未知")
                    + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", ""));
            date = blankTo(date, LocalDate.now().toString());
            model = blankTo(model, "未知");
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }

        /** 只给出工具清单与子代理开关的便捷构造。 */
        public static PromptContext of(List<String> toolNames, boolean hasSubAgents) {
            return new PromptContext(null, null, null, null, toolNames, hasSubAgents);
        }

        /** 是否具备命令执行类工具。 */
        public boolean supportsCommand() {
            return hasCommandTool(toolNames);
        }

        /** 是否具备写文件类工具。 */
        public boolean supportsWrite() {
            return hasWriteTool(toolNames);
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
