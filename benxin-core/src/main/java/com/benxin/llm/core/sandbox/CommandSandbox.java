package com.benxin.llm.core.sandbox;

import com.benxin.llm.core.tool.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 命令门禁：把 {@link SandboxConfig} 变成一道"命令能不能跑"的闸门。
 *
 * <p>只对<b>命令类调用</b>生效——判定标准是参数里出现了 {@code command / cmd / script}
 * 这类键；其它工具直接放行（路径与写权限由 {@link PathSandbox} 负责）。</p>
 *
 * <p>三道关卡，从默认最保守的一侧开始：</p>
 * <ol>
 *   <li>{@code allowExec=false}（默认）→ 一律拒绝，并告诉使用者怎么开启；</li>
 *   <li>黑名单命中 → 拒绝。黑名单默认覆盖抹盘、fork bomb、格盘、关机、改账号/注册表、
 *       "下载即执行"等"一条命令就能造成不可逆破坏"的操作；</li>
 *   <li>白名单非空 → 命令必须命中白名单前缀，否则拒绝（白名单为空时不做限制，
 *       即"只受黑名单约束"）。</li>
 * </ol>
 *
 * <p><b>匹配规则</b>（条目与命令都会做"折叠空白 + 转小写"预处理后再比）：</p>
 * <ul>
 *   <li>条目含 {@code ...} → 当作通配符，用正则在整个命令里查找（如 {@code curl ... | sh}）；</li>
 *   <li>条目含空格 → "包含"匹配（多词条目已经足够具体，如 {@code rm -rf /}、{@code net user}）；</li>
 *   <li>条目是纯命令名单词且长度 &gt; 6 → 既要"命令位置 token"相等，也允许整串"包含"
 *       （覆盖 {@code sudo shutdown}、{@code && reboot}、{@code /sbin/halt} 这类变形）；</li>
 *   <li>条目是短单词（如 {@code format}、{@code halt}、{@code mkfs}）→ 只比"命令位置 token"
 *       （允许 {@code mkfs.ext4} 这种 {@code .} 后缀、允许 {@code /sbin/xxx} 路径形式），
 *       并先剥掉 {@code sudo / nohup / env / time / nice / xargs} 这类前缀词。
 *       这样 {@code npm run format} 不会被误杀，而 {@code format c:} 会被拦住；</li>
 *   <li>其余（含括号、冒号、重定向符等标点的条目，例如 fork bomb 的特征串）→ "包含"匹配。</li>
 * </ul>
 */
public class CommandSandbox implements ToolSandbox {

    private static final Logger log = LoggerFactory.getLogger(CommandSandbox.class);

    /** 承载"要执行的命令"语义的参数键（归一化后）。 */
    private static final Set<String> COMMAND_KEYS = Set.of(
            "command", "cmd", "script", "shellcommand", "commandline");

    /** 子命令分隔符：{@code && || ; &} 与换行。管道不切分，否则 {@code git log | head} 会被误伤。 */
    private static final Pattern SUB_COMMAND_SPLIT = Pattern.compile("\\s*(?:&&|\\|\\||;|&|\\r?\\n)\\s*");

    /** 需要先剥掉的"权限提升 / 包装"前缀词，剥完剩下的第一个 token 才是真正的命令名。 */
    private static final Set<String> WRAPPER_WORDS = Set.of(
            "sudo", "doas", "runas", "su", "nohup", "env", "time", "nice", "ionice",
            "command", "builtin", "exec", "xargs", "start", "setsid", "stdbuf");

    /** 覆盖"长单词也允许包含匹配"的长度阈值。 */
    private static final int CONTAINS_MATCH_MIN_LENGTH = 7;

    private final SandboxConfig config;

    public CommandSandbox(SandboxConfig config) {
        if (config == null) {
            throw new SandboxException("CommandSandbox 需要非空的 SandboxConfig");
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
        Map<String, Object> arguments = invocation.arguments();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!COMMAND_KEYS.contains(normalizeKey(entry.getKey()))) {
                continue;
            }
            if (!(entry.getValue() instanceof CharSequence value)) {
                continue;
            }
            String command = value.toString();
            if (command.isBlank()) {
                continue;
            }
            String reason = denyReason(config, command);
            if (reason != null) {
                return Decision.deny(reason);
            }
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

    /**
     * 判断一条命令是否应当被拒绝。
     *
     * <p>做成静态方法是为了让 {@code BashTool} 能在执行前再自查一次（纵深防御：
     * 即使宿主把沙箱换成了宽松实现，命令工具自己也守着黑名单）。</p>
     *
     * @return 拒绝原因（中文，可直接回灌给模型）；允许执行时返回 {@code null}
     */
    public static String denyReason(SandboxConfig config, String command) {
        if (command == null || command.isBlank()) {
            return "拒绝执行空命令。";
        }
        if (command.indexOf('\0') >= 0) {
            return "拒绝执行含 NUL 字符的命令（非法输入）。";
        }
        String flat = squash(command).toLowerCase(Locale.ROOT);

        // 关卡一：总开关。默认关闭，必须显式打开。
        if (!config.allowExec()) {
            return "拒绝执行命令 [" + abbreviate(command) + "]：当前沙箱 allowExec=false（默认禁止在本机执行命令）。"
                    + "开启方式：SandboxConfig.builder().allowExec(true).build()，"
                    + "或 BuiltinToolkit.builder().allowExec(true)；建议同时用 allowedCommands 设置命令白名单。";
        }

        // 关卡二：高危黑名单。
        String denied = matchDenied(config.deniedCommands(), flat);
        if (denied != null) {
            return "拒绝执行命令 [" + abbreviate(command) + "]：命中高危命令黑名单条目 \"" + denied
                    + "\"（这类操作可能造成不可逆的数据或系统破坏）。"
                    + "如确需执行，请通过 SandboxConfig.builder().deniedCommands(...) 显式调整黑名单。";
        }

        // 关卡三：白名单（为空则不限制，仅受黑名单约束）。
        List<String> allowed = config.allowedCommands();
        if (!allowed.isEmpty()) {
            String missed = firstNotAllowed(allowed, command);
            if (missed != null) {
                return "拒绝执行命令 [" + abbreviate(command) + "]：子命令 \"" + abbreviate(missed)
                        + "\" 未命中命令白名单 " + allowed + "。"
                        + "allowedCommands 非空时只放行这些前缀开头的命令；"
                        + "如需放开请通过 SandboxConfig.builder().allowedCommands(...) 追加。";
            }
        }
        return null;
    }

    // ---------- 黑名单匹配 ----------

    private static String matchDenied(List<String> deniedCommands, String flatCommand) {
        for (String entry : deniedCommands) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (matchesDeniedEntry(entry, flatCommand)) {
                return entry.trim();
            }
        }
        return null;
    }

    private static boolean matchesDeniedEntry(String entry, String flatCommand) {
        String cleaned = squash(entry).toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return false;
        }
        // 1) 含 "..." 的条目：通配正则，整串查找。
        if (cleaned.contains("...")) {
            return wildcardPattern(cleaned).matcher(flatCommand).find();
        }
        // 2) 多词条目：包含匹配。
        if (cleaned.indexOf(' ') >= 0) {
            return flatCommand.contains(cleaned);
        }
        // 3) 含标点的条目（fork bomb ":(){"、PowerShell "iex(" 等）：包含匹配。
        if (!isPlainWord(cleaned)) {
            return flatCommand.contains(cleaned);
        }
        // 4) 纯单词条目：先看"命令位置 token"。
        for (String sub : SUB_COMMAND_SPLIT.split(flatCommand)) {
            String head = commandHead(sub);
            if (head != null && tokenMatches(cleaned, head)) {
                return true;
            }
        }
        // 5) 足够长的词（shutdown / bcdedit / invoke-expression ...）整串包含也算命中，
        //    覆盖 "powershell -c Invoke-Expression ..." 这类命令名不在首位的写法。
        return cleaned.length() >= CONTAINS_MATCH_MIN_LENGTH && flatCommand.contains(cleaned);
    }

    private static Pattern wildcardPattern(String entry) {
        String[] parts = entry.split("\\.\\.\\.", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    // ---------- 白名单匹配 ----------

    private static String firstNotAllowed(List<String> allowed, String command) {
        for (String sub : SUB_COMMAND_SPLIT.split(command)) {
            String trimmed = sub.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String flatSub = squash(trimmed).toLowerCase(Locale.ROOT);
            String head = commandHead(flatSub);
            boolean ok = false;
            for (String prefix : allowed) {
                String cleaned = squash(prefix).toLowerCase(Locale.ROOT);
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (cleaned.indexOf(' ') >= 0) {
                    // 多词前缀按整串前缀比较，例如 allowedCommands("git status")。
                    ok = flatSub.equals(cleaned) || flatSub.startsWith(cleaned + " ");
                } else {
                    ok = head != null && tokenMatches(cleaned, head);
                }
                if (ok) {
                    break;
                }
            }
            if (!ok) {
                return trimmed;
            }
        }
        return null;
    }

    // ---------- 通用工具方法 ----------

    /** 取一条子命令"命令位置"的 token：剥掉 sudo/env 之类包装词与引号。 */
    private static String commandHead(String subCommand) {
        if (subCommand == null) {
            return null;
        }
        String[] tokens = squash(subCommand).split(" ");
        for (String raw : tokens) {
            String token = stripToken(raw);
            if (token.isEmpty()) {
                continue;
            }
            if (WRAPPER_WORDS.contains(token) || token.startsWith("-") || token.startsWith("/c")
                    || token.startsWith("/k")) {
                continue; // sudo env -i mkfs ... 、cmd /c xxx 这类前置词
            }
            return token;
        }
        return null;
    }

    /** 判断 token 是否命中条目：全等、路径文件名相等、Windows 的 .exe 后缀、mkfs.ext4 形式。 */
    private static boolean tokenMatches(String entry, String token) {
        if (entry.equals(token)) {
            return true;
        }
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        String base = slash >= 0 ? token.substring(slash + 1) : token;
        if (entry.equals(base)) {
            return true;
        }
        if (base.endsWith(".exe") && entry.equals(base.substring(0, base.length() - 4))) {
            return true;
        }
        // mkfs -> mkfs.ext4 / mkfs.xfs
        return base.startsWith(entry + ".");
    }

    private static String stripToken(String token) {
        String value = token;
        String edges = "\"'`(){}[]$&";
        int start = 0;
        int end = value.length();
        while (start < end && edges.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && edges.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** 折叠空白：把连续空白压成一个空格并去首尾。 */
    private static String squash(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static boolean isPlainWord(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '+' || c == '-';
            if (!plain) {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(截断)";
    }

    /** 便于调试：列出当前生效的黑名单。 */
    public List<String> deniedCommands() {
        return new ArrayList<>(config.deniedCommands());
    }
}
