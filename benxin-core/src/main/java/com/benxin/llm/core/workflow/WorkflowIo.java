package com.benxin.llm.core.workflow;

import com.benxin.llm.core.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流定义的读写：YAML 与 JSON 两条入口，共用同一个模型。
 *
 * <p><b>两种格式都支持，但默认推荐 YAML。</b>理由是工作流的提示词往往是多行文本，
 * YAML 的块标量（{@code |}）能原样保留换行，而 JSON 里得写成 {@code \n} 转义 ——
 * 一份几十行的提示词被转义成一行，就没法读了。JSON 则适合机器生成与程序内往返。</p>
 *
 * <p>两条入口最终都汇到 {@code Map/List} 结构再交给 Jackson 绑定，因此
 * <b>校验逻辑只有一份</b>：无论从哪种格式来，缺 {@code prompt}、边指向不存在的节点、
 * 条件表达式写错，都会在加载期报出同一句话。</p>
 */
public final class WorkflowIo {

    /** 定义格式。 */
    public enum Format {
        /** 按首个非空字符自动判断：{@code {@}} 开头为 JSON，否则 YAML。 */
        AUTO,
        JSON,
        YAML
    }

    private WorkflowIo() {
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 自动识别格式后解析。 */
    public static WorkflowDefinition read(String text) {
        return read(text, Format.AUTO);
    }

    public static WorkflowDefinition read(String text, Format format) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("工作流定义内容为空");
        }
        String content = stripBom(text);
        Format resolved = format == null || format == Format.AUTO ? detect(content) : format;
        Object tree = switch (resolved) {
            case JSON -> parseJson(content);
            case YAML -> YamlSupport.load(content);
            case AUTO -> throw new IllegalStateException("AUTO 应已被解析为具体格式");
        };
        return bind(tree, resolved);
    }

    /**
     * 解析 JSON；失败时把"自动识别的结果 + 该往哪查"写进报错里。
     *
     * <p>YAML 的 flow 风格（{@code {name: x, nodes: [...]}}）同样以 <code>{</code> 开头，
     * 会被 {@link Format#AUTO} 认成 JSON，随后抛出的"非法 JSON"把排查方向引到"括号写错了"，
     * 而真正的问题是格式判定。这里再探一次：若"按 JSON 失败、按 YAML 却能解析"，
     * 就如实说明这是 YAML 的 flow 风格，并给出可操作的两种改法。</p>
     */
    private static Object parseJson(String content) {
        try {
            return Json.parse(content);
        } catch (RuntimeException e) {
            String hint = parsesAsYaml(content)
                    ? "注意：这份内容能被 YAML 解析 —— 它其实是 YAML 的 flow 风格（YAML 里 "
                        + "{a: 1} 这种写法合法），不是 JSON。请显式声明格式"
                        + "（llm.workflows.<key>.format: yaml，或代码里 WorkflowIo.read(text, Format.YAML)），"
                        + "或者把它改写成块状 YAML 语法"
                    : "请检查括号/逗号/引号是否匹配；如果内容其实是 YAML，请显式声明格式"
                        + "（llm.workflows.<key>.format: yaml）";
            throw new IllegalArgumentException("工作流定义看起来像 JSON（首个非空字符是 '{'）但解析失败："
                    + e.getMessage() + "。" + hint, e);
        }
    }

    private static boolean parsesAsYaml(String content) {
        try {
            YamlSupport.load(content);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static WorkflowDefinition read(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("工作流定义路径为空");
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return read(text, formatOfFileName(path.getFileName().toString()));
        } catch (IOException e) {
            throw new IllegalArgumentException("读取工作流定义失败：" + path + "（" + e.getMessage() + "）", e);
        }
    }

    public static WorkflowDefinition read(InputStream in) {
        if (in == null) {
            throw new IllegalArgumentException("工作流定义输入流为空");
        }
        try {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("读取工作流定义失败：" + e.getMessage(), e);
        }
    }

    /** 按文件扩展名推断格式；{@code .json} 才是 JSON，其余按 YAML 处理。 */
    public static Format formatOfFileName(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? Format.JSON
                : Format.YAML;
    }

    private static Format detect(String content) {
        String trimmed = content.stripLeading();
        return trimmed.startsWith("{") ? Format.JSON : Format.YAML;
    }

    private static WorkflowDefinition bind(Object tree, Format format) {
        try {
            return Json.mapper().convertValue(tree, WorkflowDefinition.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("工作流定义（" + format.name() + "）解析失败："
                    + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.toString() : message;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** YAML 入口是否可用（取决于类路径上有没有 SnakeYAML）。 */
    public static boolean yamlAvailable() {
        return YamlSupport.available();
    }

    /** 不可用时给出可执行的原因说明。 */
    public static String yamlUnavailableReason() {
        return YamlSupport.unavailableReason();
    }

    /** 序列化成 JSON 文本（格式化输出）。 */
    public static String writeJson(WorkflowDefinition definition) {
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(toMap(definition));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("工作流序列化失败", e);
        }
    }

    /** 序列化成 YAML 文本；SnakeYAML 不在类路径上时返回空。 */
    public static Optional<String> writeYaml(WorkflowDefinition definition) {
        if (!YamlSupport.available()) {
            return Optional.empty();
        }
        return Optional.of(YamlSupport.dump(toMap(definition)));
    }

    /**
     * 把定义摊平成普通 Map。
     *
     * <p>刻意手写而不是让 Jackson 直接序列化模型对象：模型用的是
     * {@code name()} / {@code nodes()} 这种无 {@code get} 前缀的访问器，
     * 而且我们<b>希望默认值不出现在输出里</b>（{@code retry: 0}、{@code maxSteps: -1}
     * 写进去只会让示例变吵）。手写一层换来的是干净、稳定、可读的往返格式。</p>
     */
    public static Map<String, Object> toMap(WorkflowDefinition definition) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", definition.name());
        putIfPresent(root, "description", definition.description());
        putIfPresent(root, "entry", definition.entry());
        if (definition.maxVisitsPerNode() != WorkflowDefinition.DEFAULT_MAX_VISITS) {
            root.put("maxVisitsPerNode", definition.maxVisitsPerNode());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (WorkflowNode node : definition.nodes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.id());
            item.put("type", node.type().wireName());
            putIfPresent(item, "description", node.description());
            putIfPresent(item, "instruction", node.instruction());
            putIfPresent(item, "prompt", node.prompt());
            putIfPresent(item, "tool", node.tool());
            if (!node.args().isEmpty()) {
                item.put("args", node.args());
            }
            if (!node.set().isEmpty()) {
                item.put("set", node.set());
            }
            putIfPresent(item, "output", node.output());
            if (node.retry() > 0) {
                item.put("retry", node.retry());
            }
            if (node.continueOnError()) {
                item.put("continueOnError", true);
            }
            if (node.maxSteps() > 0) {
                item.put("maxSteps", node.maxSteps());
            }
            nodes.add(item);
        }
        root.put("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (WorkflowEdge edge : definition.edges()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", edge.from());
            item.put("to", edge.to());
            putIfPresent(item, "when", edge.when());
            putIfPresent(item, "label", edge.label());
            edges.add(item);
        }
        root.put("edges", edges);
        return root;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
