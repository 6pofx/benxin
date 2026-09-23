package com.benxin.llm.core.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具沙箱配置：不可变值对象 + 链式 Builder。
 *
 * <p><b>为什么每个默认值都取最保守的一侧：</b>本心内置的 {@code read / write / edit / glob /
 * grep / bash} 是真能读写本地文件、真能执行本地命令的工具。默认放开等于把用户的磁盘
 * 直接交给模型，因此这里的默认姿态是"只读 + 禁止执行 + 屏蔽敏感文件"，
 * 任何一项放宽都必须由使用者显式写出来。这条原则贯穿本类的每一个默认值。</p>
 *
 * <p>本对象不可变，可安全地被多个工具、多个线程共享；需要派生变体时用 {@link #toBuilder()}。</p>
 *
 * @see PathSandbox
 * @see CommandSandbox
 */
public final class SandboxConfig {

    /**
     * 单文件读取上限：1MB。
     *
     * <p>为什么是 1MB：模型上下文极其昂贵，一次误读几十 MB 的日志或 jar 会直接撑爆
     * 上下文并烧掉大量 token。1MB 足够覆盖绝大多数源码文件，同时把最坏情况钉死。</p>
     */
    public static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;

    /**
     * 单次工具输出字符上限：30000。
     *
     * <p>为什么是 30000：约等于 1 万 token 量级，既能让模型看清一段完整报错，
     * 又不会因为一次 {@code cat} 就把上下文吃光。</p>
     */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 30_000;

    /** 单次命令执行超时：60 秒。超过即强杀，避免一个挂死的命令拖死整条 Agent 链路。 */
    public static final Duration DEFAULT_EXEC_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 默认高危命令黑名单。
     *
     * <p>匹配规则见 {@link CommandSandbox}：含 {@code ...} 的条目按通配正则匹配，
     * 含空格的条目按"包含"匹配，单 token 条目按"命令位置 token"匹配。
     * 这些条目覆盖的是"一条命令就能造成不可逆破坏"的操作，宁可误杀不可放过。</p>
     */
    public static final List<String> DEFAULT_DENIED_COMMANDS = List.of(
            // ---- 抹盘系 ----
            "rm -rf /",                      // 直接抹掉根文件系统
            "rm -rf /*",                     // 同上，带通配
            "rm -rf ~",                      // 抹掉用户主目录
            "rm -rf --no-preserve-root",     // 明知故犯式的强制抹盘
            "del /f /s /q c:\\",             // Windows 递归强删 C 盘
            "rd /s /q c:\\",
            "Remove-Item -Recurse -Force C:\\",
            // ---- fork bomb ----
            ":(){",                          // bash fork bomb 特征串
            // ---- 磁盘与分区 ----
            "mkfs",                          // 格式化文件系统（mkfs / mkfs.ext4 ...）
            "dd if=",                        // 裸写块设备
            "fdisk",
            "parted",
            "wipefs",
            "diskpart",
            "format ",                       // Windows 磁盘格式化
            "> /dev/sd",                     // 重定向覆盖整块磁盘
            // ---- 关机 / 断电系 ----
            "shutdown",
            "reboot",
            "halt",
            "poweroff",
            // ---- 权限、账号与系统配置 ----
            "chmod -R 777 /",
            "net user",                      // 增删改本机账号
            "reg delete",                    // 删注册表
            "bcdedit",                       // 改引导配置
            "vssadmin",                      // 删卷影副本（勒索软件常见前奏）
            // ---- 远程代码直执行 ----
            "curl ... | sh",
            "wget ... | sh",
            "Invoke-Expression",
            "iex("
    );

    /**
     * 默认禁止访问的路径规则。
     *
     * <p>为什么默认拦这些：它们全部是"一旦泄露即事故、一旦误改即灾难"的路径——
     * 版本库元数据、环境变量、私钥与证书库、云凭证。这些文件对正常编码任务毫无必要，
     * 拦掉的成本几乎为零，放过的代价却可能是密钥外泄。</p>
     */
    public static final List<String> DEFAULT_DENIED_PATH_PATTERNS = List.of(
            ".git/",              // Git 元数据：误改直接毁仓库
            ".env",               // 环境变量文件，几乎必然含密钥
            "id_rsa",             // SSH 私钥
            "id_dsa",
            "id_ecdsa",
            "id_ed25519",
            "*.pem",              // 证书 / 私钥
            "*.key",
            "*.p12",              // 密钥库
            "*.jks",
            "*.keystore",
            "**/credentials*",    // 常见云凭证文件命名
            "**/secrets*",
            ".ssh/",
            ".aws/",
            ".gnupg/",
            ".npmrc",             // 包管理器 token
            ".netrc",
            ".git-credentials"
    );

    private static final SandboxConfig DEFAULTS = new Builder().build();

    private final Path workdir;
    private final boolean allowWrite;
    private final boolean allowExec;
    private final List<String> allowedCommands;
    private final List<String> deniedCommands;
    private final long maxFileBytes;
    private final int maxOutputChars;
    private final Duration execTimeout;
    private final List<String> deniedPathPatterns;

    private SandboxConfig(Builder builder) {
        this.workdir = builder.workdir;
        this.allowWrite = builder.allowWrite;
        this.allowExec = builder.allowExec;
        this.allowedCommands = List.copyOf(builder.allowedCommands);
        this.deniedCommands = List.copyOf(builder.deniedCommands);
        this.maxFileBytes = builder.maxFileBytes;
        this.maxOutputChars = builder.maxOutputChars;
        this.execTimeout = builder.execTimeout;
        this.deniedPathPatterns = List.copyOf(builder.deniedPathPatterns);
    }

    /** 全默认配置：当前工作目录为围栏，只读、禁止执行、屏蔽敏感路径。 */
    public static SandboxConfig defaults() {
        return DEFAULTS;
    }

    /** 从默认值出发的 Builder（只需覆盖想改的项）。 */
    public static Builder builder() {
        return new Builder();
    }

    // ---------- 访问器 ----------

    /** 围栏根目录：所有工具路径都必须落在它之下。 */
    public Path workdir() {
        return workdir;
    }

    /** 是否允许写类工具落地（默认 false）。 */
    public boolean allowWrite() {
        return allowWrite;
    }

    /** 是否允许执行本地命令（默认 false）。 */
    public boolean allowExec() {
        return allowExec;
    }

    /** 命令白名单前缀；为空表示"不做白名单限制，仅受黑名单约束"（默认空）。 */
    public List<String> allowedCommands() {
        return allowedCommands;
    }

    /** 命令黑名单；默认给了一组高危命令。 */
    public List<String> deniedCommands() {
        return deniedCommands;
    }

    /** 单文件读取上限（字节）。 */
    public long maxFileBytes() {
        return maxFileBytes;
    }

    /** 单次结果输出上限（字符）。 */
    public int maxOutputChars() {
        return maxOutputChars;
    }

    /** 单次命令执行超时。 */
    public Duration execTimeout() {
        return execTimeout;
    }

    /** 禁止访问的路径 glob 规则。 */
    public List<String> deniedPathPatterns() {
        return deniedPathPatterns;
    }

    // ---------- 派生视图 ----------

    /** 围栏根目录的绝对规范化路径（不解析符号链接）。 */
    public Path workdirAbsolute() {
        return workdir.toAbsolutePath().normalize();
    }

    /**
     * 围栏根目录的真实路径（解析符号链接）。
     *
     * <p>路径围栏必须按真实路径比较，否则 {@code workdir/evil-link/../../etc/passwd}
     * 这类组合可以通过字符串层面的 {@code startsWith} 检查。目录不存在时退回绝对路径。</p>
     */
    public Path workdirReal() {
        Path absolute = workdirAbsolute();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            return absolute;
        }
    }

    /** 围栏的人类可读描述，用于拼装可操作的错误提示。 */
    public String fence() {
        return workdirAbsolute().toString();
    }

    /** 派生一个可修改的 Builder（复制当前全部取值）。 */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.workdir = this.workdir;
        builder.allowWrite = this.allowWrite;
        builder.allowExec = this.allowExec;
        builder.allowedCommands = new ArrayList<>(this.allowedCommands);
        builder.deniedCommands = new ArrayList<>(this.deniedCommands);
        builder.maxFileBytes = this.maxFileBytes;
        builder.maxOutputChars = this.maxOutputChars;
        builder.execTimeout = this.execTimeout;
        builder.deniedPathPatterns = new ArrayList<>(this.deniedPathPatterns);
        return builder;
    }

    @Override
    public String toString() {
        return "SandboxConfig{workdir=" + fence()
                + ", allowWrite=" + allowWrite
                + ", allowExec=" + allowExec
                + ", allowedCommands=" + allowedCommands.size()
                + ", deniedCommands=" + deniedCommands.size()
                + ", maxFileBytes=" + maxFileBytes
                + ", maxOutputChars=" + maxOutputChars
                + ", execTimeout=" + execTimeout
                + ", deniedPathPatterns=" + deniedPathPatterns.size() + "}";
    }

    /** 配置构建器。所有字段的初值即"最保守默认值"。 */
    public static final class Builder {

        private Path workdir = defaultWorkdir();
        private boolean allowWrite;
        private boolean allowExec;
        private List<String> allowedCommands = new ArrayList<>();
        private List<String> deniedCommands = new ArrayList<>(DEFAULT_DENIED_COMMANDS);
        private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        private int maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS;
        private Duration execTimeout = DEFAULT_EXEC_TIMEOUT;
        private List<String> deniedPathPatterns = new ArrayList<>(DEFAULT_DENIED_PATH_PATTERNS);

        private static Path defaultWorkdir() {
            // 默认围栏 = 宿主进程启动目录（Maven / Spring Boot 场景下就是项目根）。
            // 选它是因为它天然是"用户当前关心的那棵树"，比磁盘根或用户主目录小得多。
            String dir = System.getProperty("user.dir");
            return Paths.get(dir == null || dir.isBlank() ? "." : dir);
        }

        public Builder workdir(Path value) {
            this.workdir = value;
            return this;
        }

        public Builder workdir(String value) {
            this.workdir = value == null || value.isBlank() ? null : Paths.get(value);
            return this;
        }

        /** 打开写权限。默认 false：不显式调用就不允许任何写类工具落地。 */
        public Builder allowWrite(boolean value) {
            this.allowWrite = value;
            return this;
        }

        /** 打开命令执行权限。默认 false：不显式调用就一行命令都跑不了。 */
        public Builder allowExec(boolean value) {
            this.allowExec = value;
            return this;
        }

        /** 用给定列表整体替换命令白名单。 */
        public Builder allowedCommands(List<String> values) {
            this.allowedCommands = clean(values);
            return this;
        }

        public Builder allowedCommands(String... values) {
            return allowedCommands(values == null ? List.of() : List.of(values));
        }

        public Builder addAllowedCommand(String value) {
            if (value != null && !value.isBlank()) {
                this.allowedCommands.add(value.trim());
            }
            return this;
        }

        /** 用给定列表整体替换命令黑名单（传空列表即关闭黑名单，慎用）。 */
        public Builder deniedCommands(List<String> values) {
            this.deniedCommands = clean(values);
            return this;
        }

        public Builder deniedCommands(String... values) {
            return deniedCommands(values == null ? List.of() : List.of(values));
        }

        public Builder addDeniedCommand(String value) {
            if (value != null && !value.isBlank()) {
                this.deniedCommands.add(value.trim());
            }
            return this;
        }

        public Builder maxFileBytes(long value) {
            this.maxFileBytes = value;
            return this;
        }

        public Builder maxOutputChars(int value) {
            this.maxOutputChars = value;
            return this;
        }

        public Builder execTimeout(Duration value) {
            this.execTimeout = value;
            return this;
        }

        public Builder execTimeoutSeconds(long seconds) {
            this.execTimeout = Duration.ofSeconds(seconds);
            return this;
        }

        /** 用给定列表整体替换禁止访问路径规则。 */
        public Builder deniedPathPatterns(List<String> values) {
            this.deniedPathPatterns = clean(values);
            return this;
        }

        public Builder deniedPathPatterns(String... values) {
            return deniedPathPatterns(values == null ? List.of() : List.of(values));
        }

        public Builder addDeniedPathPattern(String value) {
            if (value != null && !value.isBlank()) {
                this.deniedPathPatterns.add(value.trim());
            }
            return this;
        }

        private static List<String> clean(List<String> values) {
            List<String> cleaned = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        cleaned.add(value.trim());
                    }
                }
            }
            return cleaned;
        }

        /**
         * 构建配置。
         *
         * <p>数值项一律"非法即报错"而不是"非法即放宽"：把 {@code maxOutputChars} 静默
         * 当成 0（等于不截断）会让一次工具调用撑爆上下文，这是配置错误应当立刻暴露的场景。</p>
         */
        public SandboxConfig build() {
            if (workdir == null) {
                throw new SandboxException("沙箱围栏根目录(workdir)不能为空");
            }
            if (maxFileBytes <= 0) {
                throw new SandboxException("maxFileBytes 必须为正数，当前为 " + maxFileBytes);
            }
            if (maxOutputChars <= 0) {
                throw new SandboxException("maxOutputChars 必须为正数，当前为 " + maxOutputChars);
            }
            if (execTimeout == null || execTimeout.isZero() || execTimeout.isNegative()) {
                throw new SandboxException("execTimeout 必须为正的时长，当前为 " + execTimeout);
            }
            return new SandboxConfig(this);
        }
    }
}
