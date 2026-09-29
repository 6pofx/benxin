package com.benxin.llm.core.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 边条件表达式。
 *
 * <p>工作流的价值有一半在分支上，因此这里给的是一门<b>小</b>语言，而不是"能跑就行"的
 * 字符串匹配。语法刻意贴近自然语言，让不写代码的人也能读懂工作流定义：</p>
 *
 * <pre>
 * ${verdict} == 通过
 * ${analyze} contains 严重
 * ${step2} matches (?i)error
 * ${summary} is not empty
 * not (${a} == 1 or ${b} &gt; 10)
 * </pre>
 *
 * <ul>
 *   <li>逻辑：{@code not} / {@code and} / {@code or}（也接受 {@code !} {@code &&} {@code ||}，
 *       以及中文的 {@code 非} {@code 且} {@code 或}），支持括号。</li>
 *   <li>比较：{@code ==} {@code !=} {@code &gt;} {@code &gt;=} {@code &lt;} {@code &lt;=}
 *       {@code contains} {@code matches}（正则，取 find 语义），
 *       {@code is empty} / {@code is not empty} / {@code exists} / {@code missing}。</li>
 *   <li>取值：{@code ${path}} 显式引用黑板；裸词<b>先当变量查、查不到才当字面量</b> ——
 *       于是 {@code 通过} 与 {@code ${通过}} 都能写，而不必强迫作者记住规则。</li>
 * </ul>
 *
 * <p><b>两处刻意的宽松</b>：一是非数字参与 {@code &gt;} 这类比较时结果为 false 而不是抛异常，
 * 因为条件写错时"走默认分支"通常比"整个工作流崩掉"更可接受；二是解析错误会在
 * <b>加载期</b>由 {@link #validate(String)} 抛出来，运行期只剩求值，不再有语法风险。</p>
 */
public final class Conditions {

    private Conditions() {
    }

    /** 语法检查：只解析不求值，供工作流加载期做静态校验。 */
    public static void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        parse(expression);
    }

    /** 求值；表达式为空视为恒真（即默认分支）。 */
    public static boolean evaluate(String expression, Function<String, Object> lookup) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        return eval(parse(expression), lookup == null ? key -> null : lookup);
    }

    // ------------------------------------------------------------------
    // 语法树
    // ------------------------------------------------------------------

    private interface Node {
        boolean eval(Function<String, Object> lookup);
    }

    private record OrNode(Node left, Node right) implements Node {
        @Override
        public boolean eval(Function<String, Object> lookup) {
            return left.eval(lookup) || right.eval(lookup);
        }
    }

    private record AndNode(Node left, Node right) implements Node {
        @Override
        public boolean eval(Function<String, Object> lookup) {
            return left.eval(lookup) && right.eval(lookup);
        }
    }

    private record NotNode(Node inner) implements Node {
        @Override
        public boolean eval(Function<String, Object> lookup) {
            return !inner.eval(lookup);
        }
    }

    private record TruthyNode(Conditions.Value value) implements Node {
        @Override
        public boolean eval(Function<String, Object> lookup) {
            return truthy(value.get(lookup));
        }
    }

    private record CompareNode(Conditions.Value left, String op, Conditions.Value right) implements Node {
        @Override
        public boolean eval(Function<String, Object> lookup) {
            Object l = left.get(lookup);
            return switch (op) {
                case "is-empty" -> isEmpty(l);
                case "is-not-empty" -> !isEmpty(l);
                case "exists" -> l != null;
                case "missing" -> l == null;
                default -> compare(l, op, right == null ? null : right.get(lookup));
            };
        }
    }

    /** 取值：字面量或黑板引用。 */
    private interface Value {
        Object get(Function<String, Object> lookup);
    }

    private record LiteralValue(Object value) implements Value {
        @Override
        public Object get(Function<String, Object> lookup) {
            return value;
        }
    }

    /** 显式引用 {@code ${path}}。 */
    private record RefValue(String path) implements Value {
        @Override
        public Object get(Function<String, Object> lookup) {
            return Templates.resolve(path, lookup);
        }
    }

    /**
     * 裸词：先按变量查，查不到就当字面量。
     *
     * <p>这个顺序是有意的 —— 让 {@code when: ${status} == done} 与
     * {@code when: ${status} == ${done}} 语义一致，同时 {@code when: ${status} == 完成}
     * 也不必加引号。取不到又不像变量名时（例如含空格的句子）仍然原样当字面量。</p>
     */
    private record BareValue(String text) implements Value {
        @Override
        public Object get(Function<String, Object> lookup) {
            Object found = Templates.resolve(text, lookup);
            return found != null ? found : text;
        }
    }

    private static boolean eval(Node node, Function<String, Object> lookup) {
        return node.eval(lookup);
    }

    // ------------------------------------------------------------------
    // 递归下降解析
    // ------------------------------------------------------------------

    private static Node parse(String expression) {
        Parser parser = new Parser(tokenize(expression), expression);
        Node node = parser.parseOr();
        parser.expectEnd();
        return node;
    }

    private static final class Parser {

        private final List<Token> tokens;
        private final String source;
        private int pos;

        Parser(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        Node parseOr() {
            Node left = parseAnd();
            while (matchKeyword("or", "||", "或", "或者")) {
                left = new OrNode(left, parseAnd());
            }
            return left;
        }

        Node parseAnd() {
            Node left = parseUnary();
            while (matchKeyword("and", "&&", "且", "并且", "而且")) {
                left = new AndNode(left, parseUnary());
            }
            return left;
        }

        Node parseUnary() {
            if (matchKeyword("not", "!", "非")) {
                return new NotNode(parseUnary());
            }
            if (peek().kind == Kind.LPAREN) {
                next();
                Node inner = parseOr();
                expect(Kind.RPAREN, "缺少右括号 ')'");
                // 括号表达式本身是布尔值，不再参与比较
                return inner;
            }
            return parseComparison();
        }

        Node parseComparison() {
            Value left = parseValue();
            Token token = peek();

            // 前缀否定：是 A not contains B 这种写法的一部分，也可能是 A not == B
            boolean negated = false;
            if (isWord(token, "not")) {
                next();
                negated = true;
                token = peek();
            }
            String word = normalize(token.text);

            // ${x} exists / ${x} missing
            if (token.kind == Kind.OP && (word.equals("exists") || word.equals("missing"))) {
                next();
                return new CompareNode(left, word, null);
            }
            // 中文空值判断：X 为空 / X 不为空（不带 is，省掉一次翻译）
            if (token.kind == Kind.OP && (word.equals("为空") || word.equals("不为空") || word.equals("非空"))) {
                next();
                boolean notEmpty = !word.equals("为空");
                if (negated) {
                    notEmpty = !notEmpty;
                }
                return new CompareNode(left, notEmpty ? "is-not-empty" : "is-empty", null);
            }
            // ${x} is empty / is not empty / 为空
            if (token.kind == Kind.OP && word.equals("is")) {
                next();
                boolean inner = matchKeyword("not", "非");
                String state = expectBare("is 之后需要 empty / 为空");
                return new CompareNode(left, stateOf(state, negated ^ inner), null);
            }
            // 值比较：contains / matches / == / != / > / >= / < / <=
            if (token.kind == Kind.OP) {
                next();
                Value right = word.equals("matches") ? parseRegexOperand() : parseValue();
                Node comparison = new CompareNode(left, token.text, right);
                return negated ? new NotNode(comparison) : comparison;
            }
            if (negated) {
                throw syntax("not 之后需要 contains / matches / is / 比较运算符，实际是 [" + token.text + "]");
            }
            // 裸值：真值判断
            return new TruthyNode(left);
        }

        private boolean isWord(Token token, String word) {
            return token.kind == Kind.BARE && normalize(token.text).equals(word);
        }

        /**
         * {@code matches} 的右操作数按<b>原文</b>读取，而不是先分词。
         *
         * <p>正则本身就是一门语言，先分词再拼回去必然会破坏它：{@code (?i)MEMORY|内存}
         * 会被拆成左括号、裸词、右括号、竖线，于是 {@code (?i)} 这个最常见的忽略大小写写法
         * 直接变成语法错误。所以这里从源码按偏移量原样截取 —— 从下一个 token 起，
         * 一直读到顶层的 {@code and} / {@code or} / {@code )} 为止。带引号时仍按字面量处理。</p>
         */
        private Value parseRegexOperand() {
            Token token = peek();
            if (token.kind == Kind.STRING) {
                next();
                return new LiteralValue(token.text);
            }
            if (token.kind == Kind.EOF) {
                throw syntax("matches 之后缺少正则表达式");
            }
            int start = token.start;
            int end = token.end;
            int depth = 0;
            while (true) {
                Token current = peek();
                if (current.kind == Kind.EOF) {
                    break;
                }
                if (current.kind == Kind.RPAREN) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (current.kind == Kind.LPAREN) {
                    depth++;
                } else if (depth == 0 && current.kind == Kind.BARE
                        && (normalize(current.text).equals("and") || normalize(current.text).equals("or"))) {
                    break;
                }
                next();
                end = current.end;
            }
            return new LiteralValue(source.substring(start, end).trim());
        }

        private String stateOf(String word, boolean negate) {
            switch (word) {
                case "empty", "blank", "空", "为空" -> {
                    return negate ? "is-not-empty" : "is-empty";
                }
                default -> throw syntax("is 之后只支持 empty / 为空，实际是 [" + word + "]");
            }
        }

        Value parseValue() {
            Token token = next();
            if (token.kind == Kind.STRING) {
                return new LiteralValue(token.text);
            }
            if (token.kind == Kind.BARE) {
                String text = token.text;
                if (text.startsWith("${") && text.endsWith("}")) {
                    return new RefValue(text.substring(2, text.length() - 1).trim());
                }
                String normalized = normalize(text);
                switch (normalized) {
                    case "true", "yes", "是" -> {
                        return new LiteralValue(Boolean.TRUE);
                    }
                    case "false", "no", "否" -> {
                        return new LiteralValue(Boolean.FALSE);
                    }
                    case "null", "nil" -> {
                        return new LiteralValue(null);
                    }
                    default -> {
                        // 数字字面量优先于变量名：写成 10 的人不会期望它是变量
                        Object number = tryNumber(text);
                        return number != null ? new LiteralValue(number) : new BareValue(text);
                    }
                }
            }
            throw syntax("表达式在 [" + token.text + "] 处缺少取值");
        }

        private Token peek() {
            return tokens.get(Math.min(pos, tokens.size() - 1));
        }

        private Token next() {
            Token token = peek();
            if (pos < tokens.size() - 1) {
                pos++;
            }
            return token;
        }

        /** 匹配一个关键字/符号，命中则消费。 */
        private boolean matchKeyword(String... candidates) {
            Token token = peek();
            if (token.kind != Kind.BARE && token.kind != Kind.OP) {
                return false;
            }
            String normalized = normalize(token.text);
            for (String candidate : candidates) {
                if (normalized.equals(normalize(candidate))) {
                    next();
                    return true;
                }
            }
            return false;
        }

        private String expectBare(String message) {
            Token token = next();
            if (token.kind != Kind.BARE) {
                throw syntax(message);
            }
            return normalize(token.text);
        }

        private void expect(Kind kind, String message) {
            if (peek().kind != kind) {
                throw syntax(message);
            }
            next();
        }

        void expectEnd() {
            Token token = peek();
            if (token.kind != Kind.EOF) {
                throw syntax("表达式在 [" + token.text + "] 之后还有多余内容");
            }
        }

        private IllegalArgumentException syntax(String message) {
            return new IllegalArgumentException("条件表达式解析失败（" + message + "）：" + source);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static Object tryNumber(String text) {
        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    private enum Kind { LPAREN, RPAREN, OP, STRING, BARE, EOF }

    private record Token(Kind kind, String text, int start, int end) {
    }

    /** 比较运算符，长符号必须排在短符号前面才能正确匹配 {@code >=}、{@code <=}、{@code ==}、{@code !=}。 */
    private static final String[] OPERATORS = {">=", "<=", "==", "!=", ">", "<"};

    private static final List<String> WORD_OPERATORS =
            List.of("contains", "matches", "exists", "missing", "is", "为空", "不为空", "非空");

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(Kind.LPAREN, "(", i, i + 1));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new Token(Kind.RPAREN, ")", i, i + 1));
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && expression.charAt(i) != c) {
                    if (expression.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    sb.append(expression.charAt(i));
                    i++;
                }
                i++; // 收尾引号
                tokens.add(new Token(Kind.STRING, sb.toString(), start, Math.min(i, n)));
                continue;
            }
            String operator = matchOperator(expression, i);
            if (operator != null) {
                tokens.add(new Token(Kind.OP, operator, i, i + operator.length()));
                i += operator.length();
                continue;
            }
            // 裸词：遇到空白或结构字符就停；但 ${...} 里的内容整体吃掉
            int start = i;
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char current = expression.charAt(i);
                if (Character.isWhitespace(current) || current == '(' || current == ')') {
                    break;
                }
                if (current == '$' && i + 1 < n && expression.charAt(i + 1) == '{') {
                    int end = expression.indexOf('}', i);
                    if (end < 0) {
                        // 不闭合的 ${ 必须在这里拦下：否则它会被当成一个裸词字面量，
                        // 于是一个语法错误会静默变成"恒为真"的分支条件。
                        throw new IllegalArgumentException(
                                "条件表达式里的 ${ 没有闭合：" + expression);
                    }
                    sb.append(expression, i, end + 1);
                    i = end + 1;
                    continue;
                }
                if (current == '!' && i + 1 < n && expression.charAt(i + 1) == '=') {
                    break;
                }
                if (current == '=' || current == '<' || current == '>') {
                    break;
                }
                if (current == '!' || current == '&' || current == '|') {
                    break;
                }
                sb.append(current);
                i++;
            }
            if (sb.length() == 0) {
                // 命中了结构字符但没被上面的分支消费：先照顾 && / || 这类双字符符号，
                // 否则它们会被拆成两个单字符 token，导致 and/or 的符号写法永远匹配不上。
                String fallback = matchOperator(expression, i);
                if (fallback == null) {
                    char current = expression.charAt(i);
                    if ((current == '&' || current == '|' || current == '!')
                            && i + 1 < n && expression.charAt(i + 1) == current) {
                        fallback = String.valueOf(current) + current;
                    } else if (current == '!' || current == '&' || current == '|') {
                        fallback = String.valueOf(current);
                    }
                }
                if (fallback == null) {
                    throw new IllegalArgumentException("条件表达式含无法识别的字符："
                            + expression.charAt(i) + "（" + expression + "）");
                }
                tokens.add(new Token(Kind.BARE, fallback, start, start + fallback.length()));
                i += fallback.length();
                continue;
            }
            String word = sb.toString();
            tokens.add(new Token(WORD_OPERATORS.contains(normalize(word)) ? Kind.OP : Kind.BARE,
                    word, start, i));
        }
        tokens.add(new Token(Kind.EOF, "", n, n));
        return tokens;
    }

    private static String matchOperator(String text, int index) {
        for (String operator : OPERATORS) {
            if (text.startsWith(operator, index)) {
                return operator;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 求值语义
    // ------------------------------------------------------------------

    private static boolean compare(Object left, String op, Object right) {
        switch (op) {
            case "==", "=" -> {
                return equalsValue(left, right);
            }
            case "!=" -> {
                return !equalsValue(left, right);
            }
            case "contains" -> {
                return contains(left, right);
            }
            case "matches" -> {
                if (left == null || right == null) {
                    return false;
                }
                try {
                    return Pattern.compile(String.valueOf(right)).matcher(String.valueOf(left)).find();
                } catch (PatternSyntaxException e) {
                    // 正则写错时判 false（走默认分支），而不是让整条工作流崩掉
                    return false;
                }
            }
            case ">", ">=", "<", "<=" -> {
                Double l = toNumber(left);
                Double r = toNumber(right);
                if (l == null || r == null) {
                    return false;
                }
                int cmp = Double.compare(l, r);
                return switch (op) {
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    default -> cmp <= 0;
                };
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        Double l = toNumber(left);
        Double r = toNumber(right);
        if (l != null && r != null) {
            return l.doubleValue() == r.doubleValue();
        }
        return String.valueOf(left).trim().equals(String.valueOf(right).trim());
    }

    private static boolean contains(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof java.util.Collection<?> collection) {
            return collection.stream().anyMatch(item -> equalsValue(item, right));
        }
        if (left instanceof Map<?, ?> map) {
            return map.containsKey(String.valueOf(right));
        }
        return String.valueOf(left).contains(String.valueOf(right));
    }

    private static Double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    /** 真值判断：null / 空串 / false / 0 / no 为假，其余为真。 */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return !lower.equals("false") && !lower.equals("no") && !lower.equals("0") && !lower.equals("null");
    }
}
