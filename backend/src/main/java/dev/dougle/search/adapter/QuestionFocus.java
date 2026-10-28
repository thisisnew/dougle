package dev.dougle.search.adapter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

final class QuestionFocus {
    private static final Set<String> REQUEST_WORDS = Set.of("예제", "예시", "좀", "주세요", "알려", "보여", "부탁", "어떻게",
            "무엇", "뭐", "어디", "이", "가", "은", "는", "을", "를", "의", "에", "도", "한", "할", "수", "있",
            "example", "examples", "please", "show", "how", "what", "where", "the", "a", "an", "me");

    private QuestionFocus() {}

    static String of(String question) {
        try (var analyzer = new KoreanAnalyzer(); var stream = analyzer.tokenStream("question", question)) {
            var attribute = stream.addAttribute(CharTermAttribute.class);
            var terms = new LinkedHashSet<String>();
            stream.reset();
            while (stream.incrementToken()) {
                String term = attribute.toString();
                if (!REQUEST_WORDS.contains(term)) terms.add(term);
            }
            stream.end();
            return terms.isEmpty() ? question : String.join(" ", terms);
        } catch (IOException exception) { return question; }
    }
}
