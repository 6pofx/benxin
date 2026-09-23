package com.benxin.llm.core.message;

/** 统一消息角色，屏蔽三家协议的命名差异。 */
public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireName;

    Role(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Role fromWire(String name) {
        if (name == null) {
            return USER;
        }
        for (Role role : values()) {
            if (role.wireName.equalsIgnoreCase(name) || role.name().equalsIgnoreCase(name)) {
                return role;
            }
        }
        // 部分兼容网关使用 developer / model 等别名
        return switch (name.toLowerCase()) {
            case "developer" -> SYSTEM;
            case "model" -> ASSISTANT;
            case "function" -> TOOL;
            default -> USER;
        };
    }
}