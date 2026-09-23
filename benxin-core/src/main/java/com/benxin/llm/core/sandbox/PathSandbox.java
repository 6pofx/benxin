package com.benxin.llm.core.sandbox;

import com.benxin.llm.core.tool.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 路径围栏：把 {@link SandboxConfig} 变成一道"文件能不能碰"的闸门。
 *
 * <p>它做三件事：</p>
 * <ol>
 *   <li><b>围栏</b>：参数里的路径解析后必须落在 {@link SandboxConfig#workdir()} 之内。
 *       判断用 {@code Path.normalize()} + {@code toAbsolutePath()} + {@code startsWith}，
 *       并且<b>按真实路径（解析符号链接）比较</b>——否则 {@code workdir/link/../../etc}
 *       这类组合可以绕过字符串前缀检查。目标还不存在时，回溯到最近的已存在祖先做真实化，
 *       再把剩余片段接回去，保证"新建文件"也逃不掉。</li>
 *   <li><b>敏感路径</b>：命中 {@link SandboxConfig#deniedPathPatterns()} 的路径一律拒绝
 *       （.git、.env、私钥、凭证文件等）。</li>
 *   <li><b>写权限</b>：写类工具在 {@code allowWrite=false}（默认）时拒绝。</li>
 * </ol>
 *
 * <p>拒绝原因一律写成可操作的中文：说明是哪个路径、触发了哪条规则、怎么开启。
 * 模型看到之后能自己换一条路走，而不是反复撞墙。</p>
 *
 * <p>{@link #truncate(String)} 沿用 {@link ToolSandbox} 的默认实现，本类只把
 * {@code maxResultChars / timeout} 对齐到配置。</p>
 */
public class PathSandbox implements ToolSandbox {

    private static final Logger log = LoggerFactory.getLogger(PathSandbox.class);

    /** 承载"路径"语义的参数键（已做去分隔符 + 小写归一化）。 */
    private static final Set<String> PATH_KEYS = Set.of(
            "path", "file", "filepath", "filename", "pattern", "dir", "directory");

    /** 纯写类工具名（小写）。这些工具的存在意义就是改文件。 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "write", "edit", "multiedit", "multi_edit", "apply_patch", "patch",
            "create_file", "str_replace_editor", "notebook_edit", "insert");

    /** 命令类工具名（小写）。 */
    private static final Set<String> EXEC_TOOLS = Set.of(
            "bash", "sh", "shell", "exec", "run_command", "powershell", "pwsh", "cmd", "terminal");

    /** 参数里出现这些键即视为写操作（覆盖自定义工具与 MCP 桥接工具）。 */
    private static final Set<String> WRITE_ARG_KEYS = Set.of(
            "content", "newstring", "newstr", "filetext", "newcontent");

    /** glob 元字符：片段里出现任意一个，就说明它不再是字面路径。 */
    private static final String GLOB_META = "*?[]{}<>\"|";

    private final SandboxConfig config;

    public PathSandbox(SandboxConfig config) {
        if (config == null) {
            throw new SandboxException("PathSandbox 需要非空的 SandboxConfig");
        }
        this.config = config;
    }

    public SandboxConfig config() {
        return config;
    }

    @Override
    public Decision check(ToolInvocation invocation) {
        if (invocation == null) {
            return Decision.allow();
        }
        String tool = invocation.toolName() == null ? "" : invocation.toolName().toLowerCase(Locale.ROOT);
        Map<String, Object> arguments = invocation.arguments();

        boolean writeTool = WRITE_TOOLS.contains(tool);
        boolean execTool = EXEC_TOOLS.contains(tool) || hasCommandArgument(arguments);
        boolean writeArgs = containsWriteArgument(arguments);

        // 1) 写权限闸门。
        //    命令类工具的门槛是"允许写 或 允许执行"：shell 本身就能写文件，所以执行权限
        //    天然蕴含写能力，没必要逼使用者同时开两个开关；而真正的命令门禁（白名单 /
        //    黑名单 / 允许执行）由 CommandSandbox 把关，那里更严。两个开关都关着时，
        //    这里先拦下来，避免把明显不该跑的东西送进命令沙箱。
        if (writeTool && !config.allowWrite()) {
            return Decision.deny("工具 [" + tool + "] 会修改本地文件，但当前沙箱 allowWrite=false（默认只读）。"
                    + "开启方式：SandboxConfig.builder().allowWrite(true).build()，"
                    + "或 BuiltinToolkit.builder().allowWrite(true)。围栏目录：" + config.fence());
        }
        if (writeArgs && !writeTool && !config.allowWrite()) {
            return Decision.deny("工具 [" + tool + "] 的参数里带有写入内容（content / new_string 等），"
                    + "但当前沙箱 allowWrite=false（默认只读）。"
                    + "开启方式：SandboxConfig.builder().allowWrite(true).build()。围栏目录：" + config.fence());
        }
        if (execTool && !config.allowWrite() && !config.allowExec()) {
            return Decision.deny("工具 [" + tool + "] 会在本机执行命令，但当前沙箱 allowExec=false（默认禁止执行）。"
                    + "开启方式：SandboxConfig.builder().allowExec(true).build()，"
                    + "或 BuiltinToolkit.builder().allowExec(true)。"
                    + "（建议同时用 allowedCommands 收紧白名单）围栏目录：" + config.fence());
        }

        // 2) 参数里的每一个路径都要过关。
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (!PATH_KEYS.contains(key) || !(entry.getValue() instanceof CharSequence value)) {
                continue;
            }
            String raw = value.toString().trim();
            if (raw.isEmpty()) {
                continue;
            }
            Decision decision = "pattern".equals(key) ? checkExpression(raw) : checkPath(key, raw);
            if (decision.denied()) {
                return decision;
            }
        }
        return Decision.allow();
    }

    /** 严格路径参数：必须可解析、必须在围栏内、不得命中敏感规则。 */
    private Decision checkPath(String key, String raw) {
        if (raw.indexOf('\0') >= 0) {
            return Decision.deny("路径参数 [" + key + "] 含 NUL 字符，属于非法路径，已拒绝。");
        }
        Resolution resolution = resolve(config, raw);
        if (!resolution.allowed()) {
            return Decision.deny(resolution.rejectReason());
        }
        return Decision.allow();
    }

    /**
     * 表达式参数（键名为 {@code pattern}）：它可能是 glob，也可能是正则，未必是路径。
     *
     * <p>因此这里只校验<b>字面前缀</b>：{@code **}{@code /*.java} 没有可校验的信息，放行；
     * {@code ../../etc/**} 的字面前缀是 {@code ../../etc}，越界即拒绝。
     * 敏感路径规则不在这里匹配——否则模型 grep 一下字面量 "id_rsa" 都会被拦，纯属误伤。
     * 真正的文件内容访问（read / write / edit）走的是严格分支，围栏并未放松。</p>
     */
    private Decision checkExpression(String raw) {
        Optional<Path> prefix = globLiteralPrefix(config, raw);
        if (prefix.isEmpty()) {
            return Decision.allow();
        }
        Path real = realOrNearest(prefix.get());
        if (!isInside(config, real)) {
            return Decision.deny("表达式 [" + raw + "] 的字面前缀解析为 " + real
                    + "，已越过沙箱围栏 " + config.fence() + "，已拒绝。"
                    + "请改用围栏内的相对路径。");
        }
        return Decision.allow();
    }

    @Override
    public Duration timeout() {
        return config.execTimeout();
    }

    @Override
    public int maxResultChars() {
        return config.maxOutputChars();
    }

    // ---------- 供内置工具复用的静态能力 ----------

    /**
     * 解析结果：要么给出可安全使用的真实路径，要么给出中文拒绝原因。
     *
     * @param path         允许时非空（已真实化 + 规范化）
     * @param rejectReason 拒绝时非空
     */
    public record Resolution(Path path, String rejectReason) {

        public static Resolution allowed(Path path) {
            return new Resolution(path, null);
        }

        public static Resolution rejected(String reason) {
            return new Resolution(null, reason);
        }

        public boolean allowed() {
            return path != null;
        }
    }

    /**
     * 把模型给的路径解析成"围栏内可安全操作的真实路径"。
     *
     * <p>内置工具都会先走这一步，不信任模型给出的任何绝对路径——即使沙箱被替换成
     * 宽松实现，工具自身仍然守着围栏（纵深防御）。</p>
     */
    public static Resolution resolve(SandboxConfig config, String raw) {
        if (raw == null || raw.isBlank()) {
            return Resolution.rejected("路径为空。");
        }
        String value = raw.trim();
        if (value.indexOf('\0') >= 0) {
            return Resolution.rejected("路径 [" + abbreviate(value) + "] 含 NUL 字符，属于非法路径，已拒绝。");
        }
        Path absolute;
        try {
            Path parsed = Paths.get(value);
            if (!parsed.isAbsolute()) {
                parsed = config.workdirAbsolute().resolve(parsed);
            }
            absolute = parsed.normalize().toAbsolutePath();
        } catch (InvalidPathException e) {
            return Resolution.rejected("路径 [" + abbreviate(value) + "] 含非法字符，无法解析："
                    + e.getMessage() + "。请改用围栏 " + config.fence() + " 内的相对路径。");
        }
        Path real = realOrNearest(absolute);
        if (!isInside(config, real)) {
            return Resolution.rejected("路径 [" + abbreviate(value) + "] 解析为 " + real
                    + "，已越过沙箱围栏 " + config.fence() + "，已拒绝。"
                    + "请改用围栏内的相对路径（例如 src/main/java/...）。");
        }
        String denied = firstDeniedPattern(config, real);
        if (denied != null) {
            return Resolution.rejected("路径 [" + abbreviate(value) + "] 命中沙箱禁止访问规则 \"" + denied
                    + "\"（默认屏蔽 .git、.env、私钥/证书、凭证文件等敏感路径），已拒绝。"
                    + "确需访问请通过 SandboxConfig.builder().deniedPathPatterns(...) 显式调整。");
        }
        return Resolution.allowed(real);
    }

    /** 路径是否落在围栏内（按真实路径比较，杜绝 {@code ..} 与符号链接绕过）。 */
    public static boolean isInside(SandboxConfig config, Path absolute) {
        if (absolute == null) {
            return false;
        }
        Path base = config.workdirReal();
        Path candidate = absolute.normalize();
        return candidate.equals(base) || candidate.startsWith(base);
    }

    /**
     * 尽量解析成真实路径：目标不存在时回溯到最近的已存在祖先做真实化，再把剩余片段接回去。
     *
     * <p>这样"新建一个文件"也享受符号链接解析，堵住 {@code workdir/link-to-outside/new.txt} 这条路。</p>
     */
    public static Path realOrNearest(Path absolute) {
        if (absolute == null) {
            return null;
        }
        Path current = absolute.normalize();
        List<Path> tail = new ArrayList<>();
        while (current != null) {
            try {
                Path real = current.toRealPath();
                for (int i = tail.size() - 1; i >= 0; i--) {
                    real = real.resolve(tail.get(i));
                }
                return real.normalize();
            } catch (IOException e) {
                Path name = current.getFileName();
                if (name != null) {
                    tail.add(name);
                }
                current = current.getParent();
            }
        }
        return absolute.normalize();
    }

    /**
     * 提取 glob / 表达式里第一段连续的"字面"前缀，用于围栏校验。
     *
     * <p>{@code src/**}{@code /*.java} → 围栏内的 {@code src}；{@code ../../etc/**} → 围栏外的
     * {@code ../../etc}；{@code **}{@code /*.java} 没有任何字面片段，返回空（无可校验信息）。</p>
     */
    public static Optional<Path> globLiteralPrefix(SandboxConfig config, String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        String normalized = expression.trim().replace('\\', '/');
        boolean absolute = normalized.startsWith("/")
                || (normalized.length() > 1 && normalized.charAt(1) == ':');
        List<String> literal = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if (containsGlobMeta(segment)) {
                break;
            }
            literal.add(segment);
        }
        try {
            if (literal.isEmpty()) {
                if (!absolute) {
                    return Optional.empty();
                }
                // 纯绝对通配（/** 或 C:/**）：前缀就是文件系统根，必然在围栏外。
                String root = normalized.startsWith("/") ? "/" : normalized.substring(0, 3);
                return Optional.of(Paths.get(root).toAbsolutePath().normalize());
            }
            Path path = Paths.get(String.join("/", literal));
            if (!path.isAbsolute()) {
                path = config.workdirAbsolute().resolve(path);
            }
            return Optional.of(path.normalize().toAbsolutePath());
        } catch (InvalidPathException e) {
            log.debug("glob 字面前缀无法解析: {}", expression);
            return Optional.empty();
        }
    }

    /** 返回命中的第一条禁止路径规则，未命中返回 {@code null}（便于把规则名写进错误提示）。 */
    public static String firstDeniedPattern(SandboxConfig config, Path absolute) {
        if (absolute == null || config.deniedPathPatterns().isEmpty()) {
            return null;
        }
        Path normalized = absolute.normalize();
        String name = normalized.getFileName() == null ? "" : normalized.getFileName().toString();
        String relative = relativize(config, normalized);
        List<String> segments = splitSegments(relative);
        for (String pattern : config.deniedPathPatterns()) {
            if (matchesDeniedPattern(pattern, name, relative, segments)) {
                return pattern;
            }
        }
        return null;
    }

    /** 路径是否命中禁止规则。 */
    public static boolean isDeniedPath(SandboxConfig config, Path absolute) {
        return firstDeniedPattern(config, absolute) != null;
    }

    private static String relativize(SandboxConfig config, Path absolute) {
        try {
            return config.workdirAbsolute().relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            // 不在同一根（例如 Windows 上跨盘符）——直接用绝对路径参与匹配。
            return absolute.toString().replace('\\', '/');
        }
    }

    private static List<String> splitSegments(String relative) {
        List<String> segments = new ArrayList<>();
        for (String segment : relative.split("/")) {
            if (!segment.isEmpty() && !".".equals(segment)) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /**
     * 单条禁止规则匹配。
     *
     * <p>用 {@link java.nio.file.FileSystems#getDefault()}{@code .getPathMatcher("glob:" + p)}
     * 构建匹配器，并同时尝试四种对象：文件相对路径、文件名、每一个路径片段、相对路径的每一段后缀。
     * 之所以要这么"啰嗦"，是因为 glob 的 {@code *} 不跨分隔符：
     * {@code *.pem} 匹配不上 {@code certs/a.pem}，{@code .git/} 也不是一条合法可比的相对路径。</p>
     */
    private static boolean matchesDeniedPattern(String pattern, String name, String relative, List<String> segments) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String cleaned = pattern.trim().replace('\\', '/');
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            return false;
        }
        List<String> variants = new ArrayList<>(2);
        variants.add(cleaned);
        if (cleaned.startsWith("**/")) {
            // Java 的 glob 里 `**/x` 至少要求一级目录，这里补一个"零级目录"的变体。
            variants.add(cleaned.substring(3));
        }
        for (String variant : variants) {
            if (variant.isEmpty()) {
                continue;
            }
            PathMatcher matcher = matcherOrNull(variant);
            if (matcher == null) {
                // 规则写错了也不能"静默失效"：退化成忽略大小写的子串比较，宁可多拦。
                log.warn("deniedPathPatterns 中的 glob 规则非法，已退化为子串匹配: {}", variant);
                if (containsIgnoreCase(relative, variant) || containsIgnoreCase(name, variant)) {
                    return true;
                }
                continue;
            }
            if (!relative.isEmpty() && safeMatches(matcher, relative)) {
                return true;
            }
            if (!name.isEmpty() && safeMatches(matcher, name)) {
                return true;
            }
            for (String segment : segments) {
                if (safeMatches(matcher, segment)) {
                    return true;
                }
            }
            for (int i = 1; i < segments.size(); i++) {
                String suffix = String.join("/", segments.subList(i, segments.size()));
                if (safeMatches(matcher, suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PathMatcher matcherOrNull(String glob) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean safeMatches(PathMatcher matcher, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            return matcher.matches(Paths.get(text));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean containsGlobMeta(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (GLOB_META.indexOf(segment.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCommandArgument(Map<String, Object> arguments) {
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (("command".equals(key) || "cmd".equals(key)) && entry.getValue() instanceof CharSequence) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWriteArgument(Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            if (WRITE_ARG_KEYS.contains(normalizeKey(key))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(截断)";
    }
}
