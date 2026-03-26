package com.tririga.custom.mcp.sample.server.model;

/**
 * Translates numeric OTM MAP_TYPE values to human-readable descriptions.
 * Kept as a separate utility so it can be reused across all tool result types.
 */
public final class MapTypeDescriptions {

    private MapTypeDescriptions() {}

    public static String describe(int mapType) {
        return switch (mapType) {
            case 5  -> "Association String";
            case 6  -> "Use Source Project";
            case 7  -> "Include Child";
            case 10 -> "Field Copy";
            case 20 -> "Smart Section";
            case 30 -> "Object-to-Section";
            case 40 -> "Literal Set";
            case 70 -> "Source Lookup";
            case 80 -> "User Defined Formula";
            case 90 -> "Association";
            default -> "Unknown (" + mapType + ")";
        };
    }
}