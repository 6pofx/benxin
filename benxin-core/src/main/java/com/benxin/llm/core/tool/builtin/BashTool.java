package com.benxin.llm.core.tool.builtin;

import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.sandbox.CommandSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行工具（{@code bash}）：在本机跑一条命令，把 stdout+stderr 合并后交回模型。
 *
 * <p><b>为什么 Windows 用 {@code cmd.exe /c} 而不是 PowerShell：</b>{@code cmd.exe} 是
 * Windows 上必然存在、且不需要任何执行策略（ExecutionPolicy）许可的 shell；
 * PowerShell 在不同版本（5.1 / 7.x）与不同机器上的可用性、别名、引号规则差异很大，
 * 还常被企业策略禁掉脚本执行。选 {@code cmd /c} 是"最不容易因为环境差异而失败"的一侧。
 * 其它平台用 {@code /bin/sh -c}：POSIX 只保证 {@code /bin/sh} 存在，
 * 它比 bash 更普遍（Alpine 等精简镜像里甚至没有 bash）。</p>
 *
 * <p>三道闸门：{@link CommandSandbox#denyReason} 会在执行前再自查一次
 * （总开关 + 黑名单 + 白名单，纵深防御），超时用 {@code destroyForcibly()} 强杀，
 * 输出按 {@link SandboxConfig#maxOutputChars()} 截断。</p>
 *
 * <p>非 0 退出码返回 {@link ToolResult#error}，但<b>仍然带上完整输出</b>——
 * 模型必须看到编译错误、堆栈、命令未找到这些信息，才能自我纠正；
 * 只回一句"命令失败"等于让它盲猜。</p>
 */
public class BashTool implements ToolCallback {

    public static final String TOOL_NAME = "bash";

    /** 输出最多缓冲多少字节（字符上限 × UTF-8 最坏 4 字节 + 余量），防止一条命令吃光内存。 */
    private static final int BYTE_BUFFER_MARGIN = 8192;

    /** 超时后等待进程真正退出的时间。 */
    private static final long KILL_GRACE_MILLIS = 2000;

    /** 允许模型自定的超时上限（秒），防止把一次工具调用挂成"永久等待"。 */
    private static final long MAX_TIMEOUT_SECONDS = 3600;

    private final SandboxConfig config;
    private final ToolSpec spec;

    public BashTool(SandboxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("BashTool 需要非空的 SandboxConfig");
        }
        this.config = config;
        this.spec = buildSpec();
    }

    private static ToolSpec buildSpec() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode command = properties.putObject("command");
        command.put("type", "string");
        command.put("description", "要执行的命令。Windows 下由 cmd.exe /c 解释，其它平台由 /bin/sh -c 解释");

        ObjectNode timeout = properties.putObject("timeout_seconds");
        timeout.put("type", "integer");
        timeout.put("description", "可选：本次命令超时秒数，默认取沙箱配置（60 秒）");

        schema.putArray("required").add("command");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "在本机执行一条命令并返回合并后的输出与退出码。"
                        + "命令必须有明确、可验证的目的；执行前会经过沙箱的命令黑/白名单校验。",
                schema);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult call(Map<String, Object> arguments, ToolContext context) {
        Process process = null;
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String command = asString(args.get("command"));
            if (command == null || command.isBlank()) {
                return ToolResult.error("参数 command 必填：请给出要执行的命令。");
            }

            // 纵深防御：即使宿主把沙箱换成了宽松实现，命令工具自己也守着总开关与黑/白名单。
            String denied = CommandSandbox.denyReason(config, command);
            if (denied != null) {
                return ToolResult.error(denied);
            }

            long timeoutMillis = resolveTimeoutMillis(args.get("timeout_seconds"));
            Path workdir = config.workdirAbsolute();

            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(workdir.toFile());
            // 合并 stderr 到 stdout：模型需要按真实顺序看到错误信息，分开读还会带来两个管道都要
            // 及时消费的复杂度（一个没读就可能把子进程写阻塞住）。
            builder.redirectErrorStream(true);

            long startedAt = System.nanoTime();
            process = builder.start();

            int byteCap = Math.max(4096, config.maxOutputChars() * 4 + BYTE_BUFFER_MARGIN);
            OutputCollector collector = new OutputCollector(process.getInputStream(), byteCap);
            Thread reader = new Thread(collector, "benxin-bash-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                reader.join(KILL_GRACE_MILLIS);
                String partial = BuiltinToolkit.truncate(config,
                        context == null ? null : context.sandbox(), collector.text());
                return ToolResult.error("命令执行超时（超过 " + (timeoutMillis / 1000) + " 秒），已强制终止："
                        + command
                        + (partial.isBlank() ? "" : "\n--- 终止前输出 ---\n" + partial)
                        + "\n[退出码: 已强制终止]");
            }

            reader.join(KILL_GRACE_MILLIS);
            int exitCode = process.exitValue();
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;

            String output = BuiltinToolkit.truncate(config,
                    context == null ? null : context.sandbox(), collector.text());
            boolean truncated = collector.truncated();
            StringBuilder body = new StringBuilder();
            body.append(output.isBlank() ? "(命令没有产生任何输出)" : output);
            if (truncated) {
                body.append("\n[输出已按 ").append(config.maxOutputChars()).append(" 字符上限截断]");
            }
            body.append("\n[退出码: ").append(exitCode).append("]");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("command", command);
            meta.put("exitCode", exitCode);
            meta.put("durationMs", durationMillis);
            meta.put("workdir", workdir.toString());
            meta.put("truncated", truncated);
            meta.put("outputBytes", collector.totalBytes());

            if (context != null) {
                try {
                    context.emit("tool.bash", Map.of(
                            "command", command,
                            "exitCode", exitCode,
                            "durationMs", durationMillis));
                } catch (RuntimeException ignored) {
                    // 事件广播失败不该影响工具结果
                }
            }

            if (exitCode != 0) {
                return ToolResult.error("命令返回非 0 退出码 " + exitCode + "：" + command + "\n" + body, meta);
            }
            return ToolResult.ok(body.toString(), meta);
        } catch (IOException e) {
            return ToolResult.error("命令启动失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolResult.error("命令执行被中断。");
        } catch (RuntimeException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolResult.error("命令执行时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        // 有副作用（改文件、改环境、动进程），并行执行结果不可预测。
        return false;
    }

    @Override
    public boolean requiresApproval() {
        // 执行命令必须人工确认：这是本插件里权限最大的一步。
        return true;
    }

    /** 按平台选择 shell 并拼装参数（原因见类注释）。 */
    private static java.util.List<String> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return java.util.List.of("cmd.exe", "/c", command);
        }
        return java.util.List.of("/bin/sh", "-c", command);
    }

    private long resolveTimeoutMillis(Object requested) {
        long defaultMillis = Math.max(1000L, config.execTimeout().toMillis());
        if (requested == null) {
            return defaultMillis;
        }
        long seconds = -1;
        if (requested instanceof Number number) {
            seconds = number.longValue();
        } else if (requested instanceof CharSequence text) {
            try {
                seconds = Long.parseLong(text.toString().trim());
            } catch (NumberFormatException ignored) {
                seconds = -1;
            }
        }
        if (seconds <= 0) {
            return defaultMillis;
        }
        return Math.min(seconds, MAX_TIMEOUT_SECONDS) * 1000L;
    }

    /**
     * 输出收集器：把子进程输出读干（防止管道写满把子进程阻塞住），
     * 但只在内存里保留前 {@code byteCap} 字节，其余丢弃并计数。
     */
    private static final class OutputCollector implements Runnable {

        private final InputStream stream;
        private final int byteCap;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile long totalBytes;
        private volatile boolean truncated;
        private volatile String text = "";

        OutputCollector(InputStream stream, int byteCap) {
            this.stream = stream;
            this.byteCap = byteCap;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) > 0) {
                    totalBytes += read;
                    int room = byteCap - buffer.size();
                    if (room > 0) {
                        buffer.write(chunk, 0, Math.min(room, read));
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时管道会断开，属于预期情况
            }
            text = decode(buffer.toByteArray());
        }

        String text() {
            return text;
        }

        long totalBytes() {
            return totalBytes;
        }

        boolean truncated() {
            return truncated;
        }

        /**
         * 字节 → 文本。
         *
         * <p>先按 UTF-8 解码；如果出现替换字符（说明子进程用的不是 UTF-8，
         * 例如中文 Windows 下 {@code cmd.exe} 默认输出 GBK），再用系统本地编码解一遍，
         * 取乱码更少的那一份。这样既能显示 UTF-8 输出，也不至于让中文环境下的报错全成乱码。</p>
         */
        private static String decode(byte[] raw) {
            if (raw.length == 0) {
                return "";
            }
            String utf8 = new String(raw, StandardCharsets.UTF_8);
            int utf8Bad = countReplacement(utf8);
            if (utf8Bad == 0) {
                return utf8;
            }
            String nativeText = new String(raw, nativeCharset());
            return countReplacement(nativeText) < utf8Bad ? nativeText : utf8;
        }

        private static int countReplacement(String text) {
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\uFFFD') {
                    count++;
                }
            }
            return count;
        }

        private static Charset nativeCharset() {
            String name = System.getProperty("native.encoding");
            if (name != null && !name.isBlank()) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // 落到默认编码
                }
            }
            return Charset.defaultCharset();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public String toString() {
        return "BashTool(" + config.fence() + ")";
    }
}
