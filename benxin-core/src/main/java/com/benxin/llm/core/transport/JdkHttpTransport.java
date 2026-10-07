package com.benxin.llm.core.transport;

import com.benxin.llm.core.model.ModelException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 基于 {@code java.net.http.HttpClient} 的默认传输实现。 */
public class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public JdkHttpTransport(HttpClient client) {
        this.client = client;
    }

    public HttpClient client() {
        return client;
    }

    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest request = build(url, headers, body, timeout);
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return TransportResponse.of(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new ModelException("请求 " + url + " 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("请求 " + url + " 被中断", e);
        }
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest request = build(url, headers, body, timeout);
        try {
            HttpResponse<java.io.InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() >= 400 || isNotEventStream(contentType)) {
                // 两种情况都把整包读出来：
                // 1. 错误响应通常是普通 JSON 而非 SSE，先读出来再抛，便于定位；
                // 2. 网关/反代把 SSE 缓冲成整包后回的是 200 + application/json。若仍按流式返回，
                //    下游 SSE 解析器一个事件都解析不到，用户看到的是"模型静默返回空回答"。
                //    如实标成非流式，HttpLlmModel 会给出可读的报错。
                String errorBody;
                try (java.io.InputStream in = response.body()) {
                    errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                return TransportResponse.of(response.statusCode(), response.headers().map(), errorBody);
            }
            return TransportResponse.streaming(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new ModelException("流式请求 " + url + " 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("流式请求 " + url + " 被中断", e);
        }
    }

    /**
     * 响应头是否明确表示"这不是一条事件流"。
     *
     * <p>只在 Content-Type 存在且不含 {@code event-stream} 时才判定为整包；
     * 头缺失时保持流式语义（有些服务端确实不发这个头），避免把正常的 SSE 读成整包。</p>
     */
    private static boolean isNotEventStream(String contentType) {
        return contentType != null && !contentType.isBlank()
                && !contentType.toLowerCase(java.util.Locale.ROOT).contains("event-stream");
    }

    private HttpRequest build(String url, Map<String, String> headers, String body, Duration timeout) {
        Map<String, String> effective = new LinkedHashMap<>();
        effective.put("Content-Type", "application/json; charset=utf-8");
        effective.put("Accept", "application/json");
        if (headers != null) {
            effective.putAll(headers);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout == null ? Duration.ofMinutes(5) : timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        effective.forEach((k, v) -> {
            if (k != null && v != null) {
                builder.header(k, v);
            }
        });
        return builder.build();
    }
}