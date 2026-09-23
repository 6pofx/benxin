package com.benxin.llm.core.tool.builtin;

import com.benxin.llm.core.sandbox.CommandSandbox;
import com.benxin.llm.core.sandbox.CompositeSandbox;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置工具集装配入口。
 *
 * <p><b>默认只给只读工具。</b>这个默认值是有意为之：read / glob / grep 只能看、
 * 不会造成任何破坏，可以放心默认打开；而 write / edit / bash 能真正改文件、跑命令，
 * 必须由使用者显式声明 {@code allowWrite(true)} / {@code allowExec(true)}
 * 或 {@code enable("write", "bash")} 才会被暴露出来。
 * 换句话说，"忘了配置"的后果是"工具少了几个"，而不是"磁盘被模型改了"。</p>
 *
 * <p>本类同时是内置工具共享能力的落脚点（目录遍历、结果截断、相对路径渲染），
 * 免得每个工具各写一份。</p>
 *
 * <pre>{@code
 * // 只读三件套（默认）
 * List<ToolCallback> tools = BuiltinToolkit.conservative();
 *
 * // 打开写权限
 * List<ToolCallback> writable = BuiltinToolkit.builder().allowWrite(true).build();
 *
 * // 全开（危险，仅限可信 / 隔离环境）
 * List<ToolCallback> everything = BuiltinToolkit.builder().all().build();
 *
 * ToolSandbox sandbox = BuiltinToolkit.sandbox(SandboxConfig.defaults());
 * }</pre>
 */
public final class BuiltinToolkit {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolkit.class);

    public static final String READ = ReadTool.TOOL_NAME;
    public static final String WRITE = WriteTool.TOOL_NAME;
    public static final String EDIT = EditTool.TOOL_NAME;
    public static final String GLOB = GlobTool.TOOL_NAME;
    public static final String GREP = GrepTool.TOOL_NAME;
    public static final String BASH = BashTool.TOOL_NAME;
    public static final String TODO_WRITE = TodoWriteTool.TOOL_NAME;
    public static final String TASK = TaskTool.TOOL_NAME;

    /** 全部内置工具名（顺序即装配顺序）。 */
    public static final List<String> ALL_TOOLS =
            List.of(READ, WRITE, EDIT, GLOB, GREP, BASH, TODO_WRITE, TASK);

    /** 默认基线：三个只读工具。没有显式授权时，装配结果就是它们。 */
    public static final List<String> READ_ONLY_TOOLS = List.of(READ, GLOB, GREP);

    /**
     * 目录遍历时整体跳过的目录名。
     *
     * <p>为什么不进去看：{@code .git} 里全是压缩对象（看到也没用还慢），
     * {@code target / node_modules / build / dist} 是构建产物（噪音，且动辄几万个文件会
     * 把一次 glob 变成磁盘风暴）。</p>
     */
    public static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "node_modules", "target", "build", "dist", "out", "bin", "obj",
            ".idea", ".gradle", ".mvn", ".settings",
            ".next", ".nuxt", ".cache", "coverage",
            "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache");

    /** 目录遍历的最大深度，防御病态深目录。 */
    public static final int MAX_WALK_DEPTH = 48;

    private BuiltinToolkit() {
    }

    // ---------- 入口 ----------

    /** 只读三件套（read / glob / grep），沙箱取 {@link SandboxConfig#defaults()}。 */
    public static List<ToolCallback> conservative() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 内置工具配套的标准沙箱：路径围栏 + 命令门禁，任一拒绝即拒绝。
     *
     * <p>请把这个沙箱交给 Agent（{@code AgentSpec.sandbox}），否则工具虽然守着围栏，
     * 但"写权限 / 执行权限"这两道闸门就没有人把关了。</p>
     */
    public static ToolSandbox sandbox(SandboxConfig config) {
        SandboxConfig effective = config == null ? SandboxConfig.defaults() : config;
        return CompositeSandbox.of(new PathSandbox(effective), new CommandSandbox(effective));
    }

    /** 全部内置工具名（只读副本）。 */
    public static List<String> toolNames() {
        return ALL_TOOLS;
    }

    // ---------- 内置工具共享能力 ----------

    /**
     * 目录遍历结果。
     *
     * @param files     找到的常规文件（已按遍历顺序）
     * @param truncated 是否因为触达 {@code maxFiles} 上限而提前收工
     */
    public record WalkResult(List<Path> files, boolean truncated) {
    }

    /**
     * 递归收集 {@code root} 下的常规文件，跳过 {@link #SKIPPED_DIRS} 与符号链接。
     *
     * <p>不跟随符号链接：既避免环，也避免顺着链接走到围栏外面去。</p>
     *
     * @param maxFiles 最多收集多少个文件（防止一次调用变成磁盘风暴）
     */
    public static WalkResult walkFiles(Path root, int maxFiles) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return new WalkResult(List.of(), false);
        }
        int limit = Math.max(1, maxFiles);
        final boolean[] truncated = {false};
        try {
            Files.walkFileTree(root, Set.of(), MAX_WALK_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        Path name = dir.getFileName();
                        if (name != null && SKIPPED_DIRS.contains(name.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        if (files.size() >= limit) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // 单个文件读不了不该让整次遍历失败
                }
            });
        } catch (IOException e) {
            log.debug("目录遍历中断: {}", root, e);
        }
        return new WalkResult(List.copyOf(files), truncated[0]);
    }

    /**
     * 按 {@link SandboxConfig#maxOutputChars()} 截断结果文本。
     *
     * <p>优先复用调用方沙箱的截断口径（这样一处配置全局一致）；沙箱缺失时退回配置里的上限。</p>
     */
    public static String truncate(SandboxConfig config, ToolSandbox sandbox, String content) {
        if (content == null) {
            return "";
        }
        if (sandbox != null) {
            return sandbox.truncate(content);
        }
        final int max = Math.max(1, config.maxOutputChars());
        ToolSandbox fallback = new ToolSandbox() {
            @Override
            public Decision check(ToolInvocation invocation) {
                return Decision.allow();
            }

            @Override
            public int maxResultChars() {
                return max;
            }
        };
        return fallback.truncate(content);
    }

    /** 渲染围栏内的相对路径（统一用 {@code /} 分隔，跨平台输出一致）。 */
    public static String relative(SandboxConfig config, Path path) {
        if (path == null) {
            return "";
        }
        try {
            return config.workdirAbsolute().relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString().replace('\\', '/');
        }
    }

    /** 便捷判断：这个目录名是否属于"遍历时跳过"的一类。 */
    public static boolean isSkippedDirectory(String name) {
        return name != null && SKIPPED_DIRS.contains(name);
    }

    /**
     * 预编译的 glob 表达式。
     *
     * <p>比裸 {@link java.nio.file.PathMatcher} 多做一件事：Java 的 glob 里
     * {@code **}{@code /x} 至少要求一级目录，于是 {@code **}{@code /*.java} 匹配不上顶层的
     * {@code A.java}，这与绝大多数使用者的直觉（以及 .gitignore 的语义）不符。
     * 这里额外编译一个"去掉前导 {@code **}/ 的零级目录"变体，两种写法都能命中。</p>
     */
    public static final class GlobPattern {

        private final String pattern;
        private final PathMatcher primary;
        private final PathMatcher zeroDepth;

        private GlobPattern(String pattern, PathMatcher primary, PathMatcher zeroDepth) {
            this.pattern = pattern;
            this.primary = primary;
            this.zeroDepth = zeroDepth;
        }

        /** 编译表达式；语法非法时抛 {@link IllegalArgumentException}（调用方转成工具错误）。 */
        public static GlobPattern compile(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("glob 表达式为空");
            }
            String normalized = raw.trim().replace('\\', '/');
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1); // 模型偶尔会写 "/**/*.java"，按相对处理
            }
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalized);
                PathMatcher fallback = normalized.startsWith("**/")
                        ? FileSystems.getDefault().getPathMatcher("glob:" + normalized.substring(3))
                        : null;
                return new GlobPattern(normalized, matcher, fallback);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("glob 表达式非法: " + raw + " - " + e.getMessage(), e);
            }
        }

        public String pattern() {
            return pattern;
        }

        /** 按"相对根的路径"匹配（glob 工具语义：{@code *.java} 只匹配顶层文件）。 */
        public boolean matchesPath(String relativePath) {
            if (relativePath == null || relativePath.isEmpty()) {
                return false;
            }
            if (safeMatches(primary, relativePath)) {
                return true;
            }
            return zeroDepth != null && safeMatches(zeroDepth, relativePath);
        }

        /** 按"文件名 + 相对路径"匹配（include 过滤器语义：{@code *.java} 匹配任意层级的 java 文件）。 */
        public boolean matchesName(String fileName, String relativePath) {
            if (fileName != null && !fileName.isEmpty() && safeMatches(primary, fileName)) {
                return true;
            }
            if (matchesPath(relativePath)) {
                return true;
            }
            return zeroDepth != null && fileName != null && !fileName.isEmpty()
                    && safeMatches(zeroDepth, fileName);
        }

        private static boolean safeMatches(PathMatcher matcher, String text) {
            try {
                return matcher.matches(Path.of(text));
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return pattern;
        }
    }

    /** 内置工具集构建器。 */
    public static final class Builder {

        private SandboxConfig config;
        private Path workdir;
        private Boolean allowWrite;
        private Boolean allowExec;
        private Long maxFileBytes;
        private Integer maxOutputChars;
        private Duration execTimeout;
        private final Set<String> enabled = new LinkedHashSet<>();
        private final Set<String> disabled = new LinkedHashSet<>();

        private Builder() {
        }

        /** 直接给定沙箱配置（与 workdir / allowWrite / allowExec 同时使用时，后者覆盖前者）。 */
        public Builder config(SandboxConfig value) {
            this.config = value;
            return this;
        }

        /** 围栏根目录；不给则用 {@code SandboxConfig} 的默认值（进程启动目录）。 */
        public Builder workdir(Path value) {
            this.workdir = value;
            return this;
        }

        public Builder workdir(String value) {
            this.workdir = value == null || value.isBlank() ? null : Path.of(value);
            return this;
        }

        /** 打开写权限。默认 false：不调用它，write / edit 不会出现在结果里也跑不动。 */
        public Builder allowWrite(boolean value) {
            this.allowWrite = value;
            return this;
        }

        /** 打开执行权限。默认 false：不调用它，bash 不会出现在结果里也跑不动。 */
        public Builder allowExec(boolean value) {
            this.allowExec = value;
            return this;
        }

        /** 单文件读取上限（字节），默认 1MB。 */
        public Builder maxFileBytes(long value) {
            this.maxFileBytes = value;
            return this;
        }

        /** 单次结果输出上限（字符），默认 30000。 */
        public Builder maxOutputChars(int value) {
            this.maxOutputChars = value;
            return this;
        }

        /** 命令默认超时，默认 60 秒。 */
        public Builder execTimeout(Duration value) {
            this.execTimeout = value;
            return this;
        }

        public Builder execTimeoutSeconds(long seconds) {
            this.execTimeout = Duration.ofSeconds(seconds);
            return this;
        }

        /**
         * 启用指定工具。
         *
         * <p>启用写类工具（write / edit）等价于同时声明 {@code allowWrite(true)}；
         * 启用 bash 等价于声明 {@code allowExec(true)}——不这么做的话，工具会被暴露出来
         * 却永远被沙箱拒绝，属于"看起来能用其实不能用"的坑。</p>
         */
        public Builder enable(String... names) {
            if (names != null) {
                for (String name : names) {
                    if (name != null && !name.isBlank()) {
                        enabled.add(name.trim());
                    }
                }
            }
            return this;
        }

        public Builder enable(List<String> names) {
            return names == null ? this : enable(names.toArray(new String[0]));
        }

        /** 禁用指定工具（优先级高于 {@link #enable}）。 */
        public Builder disable(String... names) {
            if (names != null) {
                for (String name : names) {
                    if (name != null && !name.isBlank()) {
                        disabled.add(name.trim());
                    }
                }
            }
            return this;
        }

        public Builder disable(List<String> names) {
            return names == null ? this : disable(names.toArray(new String[0]));
        }

        /**
         * 启用全部 8 个内置工具（含写入与命令执行）。
         *
         * <p><b>危险</b>：等价于把本地文件系统与 shell 一起交给模型，
         * 只应在可信环境或一次性容器里使用。</p>
         */
        public Builder all() {
            return enable(ALL_TOOLS.toArray(new String[0]));
        }

        /** 本构建器最终生效的沙箱配置。 */
        public SandboxConfig effectiveConfig() {
            SandboxConfig base = config == null ? SandboxConfig.defaults() : config;
            SandboxConfig.Builder builder = base.toBuilder();
            if (workdir != null) {
                builder.workdir(workdir);
            }
            if (maxFileBytes != null) {
                builder.maxFileBytes(maxFileBytes);
            }
            if (maxOutputChars != null) {
                builder.maxOutputChars(maxOutputChars);
            }
            if (execTimeout != null) {
                builder.execTimeout(execTimeout);
            }
            boolean write = allowWrite != null ? allowWrite : base.allowWrite();
            boolean exec = allowExec != null ? allowExec : base.allowExec();
            // 显式 enable 写/执行类工具 = 同时授予对应权限；否则工具会"看得见却永远跑不动"。
            Set<String> requested = requestedToolNames();
            if (requested.contains(WRITE) || requested.contains(EDIT)) {
                write = true;
            }
            if (requested.contains(BASH)) {
                exec = true;
            }
            return builder.allowWrite(write).allowExec(exec).build();
        }

        /** 与 {@link #build()} 配套的沙箱（路径围栏 + 命令门禁）。 */
        public ToolSandbox sandbox() {
            return BuiltinToolkit.sandbox(effectiveConfig());
        }

        /** 使用者显式 enable 且未被 disable 的工具名。 */
        private Set<String> requestedToolNames() {
            Set<String> requested = new LinkedHashSet<>(enabled);
            requested.removeAll(disabled);
            return requested;
        }

        /**
         * 生效的工具名。
         *
         * <p>规则：默认基线是只读三件套 → 叠加显式 {@code enable} → 叠加"授权即启用"
         * （{@code allowWrite(true)} 放出 write/edit，{@code allowExec(true)} 放出 bash）
         * → 最后减去 {@code disable}。因此 {@code allowWrite(true)} 与
         * {@code enable("write")} 两条路都能把写工具打开，而 {@code disable} 永远说了算。</p>
         */
        public Set<String> activeToolNames() {
            Set<String> active = new LinkedHashSet<>(READ_ONLY_TOOLS);
            active.addAll(enabled);
            SandboxConfig effective = effectiveConfig();
            if (effective.allowWrite()) {
                active.add(WRITE);
                active.add(EDIT);
            }
            if (effective.allowExec()) {
                active.add(BASH);
            }
            active.removeAll(disabled);
            active.retainAll(ALL_TOOLS);
            return active;
        }

        /**
         * 装配工具列表。
         *
         * <p>返回顺序固定为 {@link #ALL_TOOLS} 的顺序，便于测试与提示词渲染稳定。</p>
         */
        public List<ToolCallback> build() {
            SandboxConfig sandboxConfig = effectiveConfig();
            Set<String> active = activeToolNames();
            List<ToolCallback> tools = new ArrayList<>();
            for (String name : ALL_TOOLS) {
                if (!active.contains(name)) {
                    continue;
                }
                switch (name) {
                    case READ -> tools.add(new ReadTool(sandboxConfig));
                    case WRITE -> tools.add(new WriteTool(sandboxConfig));
                    case EDIT -> tools.add(new EditTool(sandboxConfig));
                    case GLOB -> tools.add(new GlobTool(sandboxConfig));
                    case GREP -> tools.add(new GrepTool(sandboxConfig));
                    case BASH -> tools.add(new BashTool(sandboxConfig));
                    case TODO_WRITE -> tools.add(new TodoWriteTool());
                    case TASK -> tools.add(new TaskTool());
                    default -> log.warn("未知内置工具名，已忽略: {}", name);
                }
            }
            log.debug("内置工具装配完成: {} （围栏 {}）", active, sandboxConfig.fence());
            return List.copyOf(tools);
        }
    }
}
