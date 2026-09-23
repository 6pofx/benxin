package com.benxin.llm.core.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 传输层响应。
 *
 * <p>同步调用看 {@link #body()}，流式调用看 {@link #stream()}，两者互斥。</p>
 */
public record TransportResponse(int status, Map<String, List<String>> headers, String body, InputStream stream)
        implements Closeable {

    public static TransportResponse of(int status, Map<String, List<String>> headers, String body) {
        return new TransportResponse(status, headers, body, null);
    }

    public static TransportResponse streaming(int status, Map<String, List<String>> headers, InputStream stream) {
        return new TransportResponse(status, headers, null, stream);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isStreaming() {
        return stream != null;
    }

    public String header(String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关闭失败无需上抛
            }
        }
    }
}