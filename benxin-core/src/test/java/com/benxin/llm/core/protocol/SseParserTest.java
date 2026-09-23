package com.benxin.llm.core.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE 解析器：三协议的流式差异最终都要落到这几条规则上。 */
class SseParserTest {

    @Test
    @DisplayName("解析 OpenAI 风格的多行 data 与 [DONE] 终止标记")
    void parsesOpenAiStyleStream() {
        String body = """
                data: {"a":1}

                data: {"b":2}

                data: [DONE]

                data: {"never":true}
                """;
        List<SseEvent> events = new ArrayList<>();
        SseParser.parse(body, events::add);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).data()).isEqualTo("{\"a\":1}");
        assertThat(events.get(1).data()).isEqualTo("{\"b\":2}");
        assertThat(events.get(2).isDone()).isTrue();
        // [DONE] 之后的内容必须被丢弃，否则某些网关的尾包会造成重复收尾
        assertThat(events).noneMatch(e -> e.data().contains("never"));
    }

    @Test
    @DisplayName("解析 Anthropic 风格的 event + data 成对事件")
    void parsesAnthropicStyleStream() {
        String body = """
                event: message_start
                data: {"type":"message_start"}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<SseEvent> events = new ArrayList<>();
        SseParser.parse(body, events::add);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("message_start");
        assertThat(events.get(1).event()).isEqualTo("content_block_delta");
        assertThat(events.get(2).event()).isEqualTo("message_stop");
    }

    @Test
    @DisplayName("多行 data 按 SSE 规范用换行拼接")
    void joinsMultiLineData() {
        String body = "data: line1\ndata: line2\n\n";
        List<SseEvent> events = new ArrayList<>();
        SseParser.parse(body, events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("line1\nline2");
    }

    @Test
    @DisplayName("忽略心跳注释行，并兼容不带空格的 data: 写法")
    void ignoresCommentsAndHandlesNoSpace() {
        String body = """
                : keep-alive
                data:{"x":1}

                """;
        List<SseEvent> events = new ArrayList<>();
        SseParser.parse(body, events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("{\"x\":1}");
    }

    @Test
    @DisplayName("流结束时冲刷没有以空行收尾的最后一个事件")
    void flushesTrailingEvent() {
        List<SseEvent> events = new ArrayList<>();
        SseParser.parse("data: {\"last\":true}", events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("{\"last\":true}");
    }

    @Test
    @DisplayName("兼容 CRLF 换行")
    void handlesCrlf() {
        List<SseEvent> events = new ArrayList<>();
        SseParser.parse("data: {\"a\":1}\r\n\r\ndata: {\"b\":2}\r\n\r\n", events::add);

        assertThat(events).hasSize(2);
        assertThat(events.get(1).data()).isEqualTo("{\"b\":2}");
    }
}