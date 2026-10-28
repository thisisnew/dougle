package dev.dougle.conversation.application;

import dev.dougle.library.application.LibraryException;
import dev.dougle.search.domain.EvidenceBundle;
import java.util.HashSet;
import java.util.ArrayList;
import tools.jackson.databind.json.JsonMapper;

public class AnswerOutput {
    private static final java.util.regex.Pattern INLINE_CITATION = java.util.regex.Pattern.compile("\\[R\\s*\\d");
    public static String validate(String raw, EvidenceBundle bundle) {
        try {
            if (raw == null || raw.length() > 65536) throw new IllegalArgumentException();
            var root = JsonMapper.builder().build().readTree(raw);
            var claims = root.path("claims");
            if (!root.isObject() || root.size() != 2 || !root.path("title").isString()
                    || root.path("title").asString().length() > 120 || !claims.isArray() || claims.size() > 30) throw new IllegalArgumentException();
            var allowed = new HashSet<String>();
            for (var item : bundle.payload().items()) {
                if (java.util.Set.of("ORIGINAL", "ATTACHMENT_IMAGE", "ATTACHMENT_TEXT").contains(item.kind())) allowed.add(item.label());
                else for (var support : item.supports()) allowed.add(item.label() + "/" + support.label());
            }
            var lines = new ArrayList<String>();
            for (var claim : claims) {
                if (!claim.isObject() || claim.size() != 2 || !claim.path("text").isString() || claim.path("text").asString().isBlank()
                        || claim.path("text").asString().length() > 2000 || !claim.path("evidenceIds").isArray()
                        || claim.path("evidenceIds").isEmpty() || claim.path("evidenceIds").size() > 20) throw new IllegalArgumentException();
                String text = claim.path("text").asString();
                if (INLINE_CITATION.matcher(text).find()) throw new IllegalArgumentException();
                var citations = new java.util.LinkedHashSet<String>();
                for (var id : claim.path("evidenceIds")) {
                    if (!id.isString() || !allowed.contains(id.asString())) throw new IllegalArgumentException();
                    citations.add("[" + id.asString() + "]");
                }
                lines.add(text + " " + String.join(" ", citations));
            }
            String answer = lines.isEmpty() ? "제공된 근거로 답변할 수 없습니다." : String.join("\n\n", lines);
            if (answer.length() > 20000) throw new IllegalArgumentException();
            return answer;
        } catch (RuntimeException exception) {
            throw new LibraryException(502, "AI 답변 형식·인용 검증에 실패했습니다. 결과를 저장하지 않았습니다.");
        }
    }
}
