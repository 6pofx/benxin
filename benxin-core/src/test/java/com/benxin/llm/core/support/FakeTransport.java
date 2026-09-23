package com.benxin.llm.core.support;

import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 假传输层：把整条模型链路（Codec → HttpLlmModel → Loop）在完全离线的环境下跑通。
 *
 * <p>这正是把 HTTP 抽成 {@code HttpTransport} 接口的收益 —— 可测试性不是事后补的，
 * 而是设计出来的。</p>
 */
public final class FakeTransport implements HttpTransport {

    private final Deque<TransportResponse> queue = new ArrayDeque<>();
    private final List<String> urls = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<Map<String, String>> headerSets = new ArrayList<>();

    public static FakeTransport returning(String body) {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, body);
        return transport;
    }

    public FakeTransport enqueue(int status, String body) {
        queue.add(TransportResponse.of(status, Map.of(), body));
        return this;
    }

    public FakeTransport enqueueStream(int status, String sseBody) {
        queue.add(TransportResponse.streaming(status, Map.of(),
                new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8))));
        return this;
    }

    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
        record(url, headers, body);
        TransportResponse response = queue.poll();
        if (response == null) {
            throw new IllegalStateException("FakeTransport 没有更多预设响应了");
        }
        return response;
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout) {
        record(url, headers, body);
        TransportResponse response = queue.poll();
        if (response == null) {
            throw new IllegalStateException("FakeTransport 没有更多预设响应了");
        }
        return response;
    }

    private void record(String url, Map<String, String> headers, String body) {
        urls.add(url);
        bodies.add(body);
        headerSets.add(headers);
    }

    public List<String> urls() {
        return List.copyOf(urls);
    }

    public List<String> bodies() {
        return List.copyOf(bodies);
    }

    public String lastBody() {
        return bodies.isEmpty() ? null : bodies.get(bodies.size() - 1);
    }

    public Map<String, String> lastHeaders() {
        return headerSets.isEmpty() ? Map.of() : headerSets.get(headerSets.size() - 1);
    }
}