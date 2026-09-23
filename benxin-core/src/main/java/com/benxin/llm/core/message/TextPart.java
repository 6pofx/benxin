package com.benxin.llm.core.message;

import java.util.Objects;

/** 文本内容块。 */
public record TextPart(String text) implements ContentPart {

    public TextPart {
        text = text == null ? "" : text;
    }

    @Override
    public String type() {
        return "text";
    }

    @Override
    public String asText() {
        return text;
    }

    @Override
    public String toString() {
        return Objects.toString(text, "");
    }
}