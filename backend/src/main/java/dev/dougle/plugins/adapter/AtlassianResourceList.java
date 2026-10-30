package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decode documented MCP list encodings, without searching arbitrary nested response data. */
final class AtlassianResourceList {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static JsonNode decode(JsonNode result) {
        try {
            JsonNode structured = result.get("structuredContent");
            if (structured != null && !structured.isNull() && !(structured.isObject() && structured.isEmpty()))
                return list(structured, 0);
            var combined = JSON.createArrayNode();
            boolean found = false;
            int length = 0;
            for (JsonNode part : result.path("content")) {
                if (!"text".equals(part.path("type").asText())) continue;
                if (!part.path("text").isTextual()) throw invalid();
                String text = part.path("text").asText();
                length += text.length();
                if (length > 2_000_000) throw invalid();
                combined.addAll((tools.jackson.databind.node.ArrayNode) list(JSON.readTree(text), 0));
                found = true;
            }
            if (!found) throw invalid();
            return combined;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static JsonNode list(JsonNode value, int depth) {
        if (value == null || depth > 3) throw invalid();
        if (value.isArray()) return value;
        if (!value.isObject()) throw invalid();
        // SDKs wrap non-object output in result; existing servers also use resources.
        if (value.has("result") && value.has("resources")) throw invalid();
        if (value.has("result")) return list(value.get("result"), depth + 1);
        if (value.has("resources")) return list(value.get("resources"), depth + 1);
        // Legacy unstructured MCP output can have one text block per resource.
        if ((value.path("id").isTextual() || value.path("cloudId").isTextual()) && value.path("url").isTextual())
            return JSON.createArrayNode().add(value);
        throw invalid();
    }

    /** Normalize only server-granted Cloud sites; never infer a grant from a user URL. */
    static JsonNode site(JsonNode value) {
        try {
            JsonNode legacy = value.get("id"), modern = value.get("cloudId");
            if (legacy != null && !legacy.isTextual() || modern != null && !modern.isTextual()) return null;
            if (legacy != null && modern != null && !legacy.asText().equals(modern.asText())) return null;
            String id = modern != null ? modern.asText() : legacy != null ? legacy.asText() : "";
            if (!id.matches("[A-Za-z0-9_-]{1,128}") || !value.path("url").isTextual()) return null;
            String raw = value.path("url").asText();
            URI uri = URI.create(raw.contains("://") ? raw : "https://" + raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(uri.getScheme()) || !host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.atlassian\\.net")
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) return null;
            return JSON.createObjectNode().put("id", id).put("url", "https://" + host);
        } catch (IllegalArgumentException failure) { return null; }
    }

    private static LibraryException invalid() {
        return new LibraryException(502, "회사 사이트 목록 형식을 확인하지 못했습니다. 앱 업데이트 후 다시 연결해 주세요.");
    }
}
