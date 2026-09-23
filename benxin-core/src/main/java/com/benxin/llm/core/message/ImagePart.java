package com.benxin.llm.core.message;

/**
 * 图片内容块，支持内联 base64 或外链 URL 两种来源。
 * 三个协议的图片编码方式不同，由各自 Codec 负责转换。
 */
public record ImagePart(String mediaType, String base64Data, String url) implements ContentPart {

    public static ImagePart ofBase64(String mediaType, String base64Data) {
        return new ImagePart(mediaType, base64Data, null);
    }

    public static ImagePart ofUrl(String url) {
        return new ImagePart(null, null, url);
    }

    @Override
    public String type() {
        return "image";
    }

    public boolean isInline() {
        return base64Data != null && !base64Data.isBlank();
    }

    /** 形如 {@code data:image/png;base64,xxxx}。 */
    public String toDataUri() {
        return "data:" + (mediaType == null ? "image/png" : mediaType) + ";base64," + base64Data;
    }
}