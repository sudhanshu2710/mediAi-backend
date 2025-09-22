package medi.ai.mediAi_backend.util;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LabStatusCalculator {

    private static final Pattern NUM_PATTERN =
            Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Takes a JSON string with test_summary entries of format:
     *   "TestName": ["value","unit","reference_range"]
     * and enriches them to:
     *   "TestName": ["value","unit","reference_range","status"]
     */
    public String addStatuses(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (!root.has("test_summary")) return json;

        ObjectNode ts = (ObjectNode) root.get("test_summary");

        ts.fieldNames().forEachRemaining(testName -> {
            ArrayNode arr = (ArrayNode) ts.get(testName);
            if (arr == null || arr.size() < 3) return;

            String valueStr = arr.get(0).asText("");
            String unitStr  = arr.get(1).asText("");
            String refStr   = arr.get(2).asText("");

            String status = computeStatus(valueStr, refStr);

            // ensure exactly 4 elements
            while (arr.size() < 4) arr.add("");
            arr.set(3, TextNode.valueOf(status));
        });

        return mapper.writeValueAsString(root);
    }

    private String computeStatus(String valueStr, String refStr) {
        BigDecimal value = parseNumber(valueStr);
        if (value == null || refStr == null || refStr.isBlank()) return "";

        refStr = refStr.replaceAll("\\s+", "");

        // --- Range a-b ---
        if (refStr.matches(".*[-–—].*")) {
            String[] parts = refStr.split("[-–—]");
            if (parts.length == 2) {
                BigDecimal low = parseNumber(parts[0]);
                BigDecimal high = parseNumber(parts[1]);
                if (low != null && high != null) {
                    if (value.compareTo(low) < 0) return "Low";
                    if (value.compareTo(high) > 0) return "High";
                    return "Normal";
                }
            }
        }

        // --- Less than or equal ---
        if (refStr.startsWith("<=") || refStr.startsWith("≤")) {
            BigDecimal max = parseNumber(refStr);
            if (max != null && value.compareTo(max) > 0) return "High";
            return "Normal";
        }

        // --- Strict less than ---
        if (refStr.startsWith("<")) {
            BigDecimal max = parseNumber(refStr);
            if (max != null && value.compareTo(max) >= 0) return "High";
            return "Normal";
        }

        // --- Greater than or equal ---
        if (refStr.startsWith(">=") || refStr.startsWith("≥")) {
            BigDecimal min = parseNumber(refStr);
            if (min != null && value.compareTo(min) < 0) return "Low";
            return "Normal";
        }

        // --- Strict greater than ---
        if (refStr.startsWith(">")) {
            BigDecimal min = parseNumber(refStr);
            if (min != null && value.compareTo(min) <= 0) return "Low";
            return "Normal";
        }

        // --- Qualitative mappings ---
        String vs = valueStr.toLowerCase();
        if (vs.contains("positive") || vs.contains("reactive") || vs.contains("detected") || vs.contains("present"))
            return "High";
        if (vs.contains("negative") || vs.contains("nonreactive") || vs.contains("notdetected"))
            return "Normal";

        return "";
    }

    private BigDecimal parseNumber(String s) {
        if (s == null) return null;
        // normalize scientific notation like "1.2 x10^3"
        s = s.replaceAll(",", "");
        s = s.replaceAll("x10\\^", "E").replaceAll("×10\\^", "E");

        Matcher m = NUM_PATTERN.matcher(s);
        if (m.find()) {
            try {
                return new BigDecimal(m.group());
            } catch (Exception ignored) { }
        }
        return null;
    }
}
