package com.koper.koper_lib.physics;

public enum AeroMode {
    LOW(1, "low"),
    CORRECT(2, "correct"),
    EXTREME(3, "extreme");

    private final int id;
    private final String key;

    AeroMode(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public int id() { return id; }
    public String key() { return key; }

    public static AeroMode byId(int id) {
        return switch (id) {
            case 1 -> LOW;
            case 3 -> EXTREME;
            default -> CORRECT;
        };
    }

    public static AeroMode parse(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "1", "low" -> LOW;
            case "2", "correct", "normal" -> CORRECT;
            case "3", "extreme", "sim" -> EXTREME;
            default -> null;
        };
    }
}
