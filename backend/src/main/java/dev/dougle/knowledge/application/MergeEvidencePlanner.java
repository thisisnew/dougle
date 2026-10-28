package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.Knowledge.Evidence;
import dev.dougle.library.application.LibraryException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class MergeEvidencePlanner {
    private static final Pattern CITATION = Pattern.compile("\\[E([^]\\r\\n]*)]");
    private record Key(String snapshot, int start, int end) {}
    public record Plan(List<Evidence> evidence, List<Integer> mapping, String generatedMarkdown) {}

    public Plan plan(List<Evidence> current, List<Evidence> generated, String markdown) {
        if (current.isEmpty() || generated.isEmpty()
                || current.stream().anyMatch(item -> !"CURRENT".equals(item.freshness()))
                || generated.stream().anyMatch(item -> !"CURRENT".equals(item.freshness()))) {
            throw new LibraryException(409, "모든 근거가 현재 수집본과 같아야 번호를 통합할 수 있습니다.");
        }
        var evidence = new ArrayList<>(current);
        var ordinals = new HashMap<Key, Integer>();
        for (int index = 0; index < evidence.size(); index++) {
            var item = evidence.get(index);
            ordinals.putIfAbsent(new Key(item.snapshotId(), item.startLine(), item.endLine()), index + 1);
        }
        var mapping = new ArrayList<Integer>();
        for (var item : generated) {
            var key = new Key(item.snapshotId(), item.startLine(), item.endLine());
            Integer ordinal = ordinals.get(key);
            if (ordinal == null) {
                evidence.add(item);
                ordinal = evidence.size();
                ordinals.put(key, ordinal);
            }
            mapping.add(ordinal);
        }
        if (evidence.size() > 10) throw new LibraryException(409, "통합 근거는 최대 10개입니다. 근거를 정리한 뒤 다시 생성하세요.");
        var matcher = CITATION.matcher(markdown);
        var converted = new StringBuilder();
        while (matcher.find()) {
            String reference = matcher.group(1);
            if (!reference.matches("[1-9][0-9]?") || Integer.parseInt(reference) > mapping.size()) {
                throw new LibraryException(400, "AI 원문의 근거 번호가 유효하지 않습니다.");
            }
            matcher.appendReplacement(converted, Matcher.quoteReplacement("[E" + mapping.get(Integer.parseInt(reference) - 1) + "]"));
        }
        matcher.appendTail(converted);
        return new Plan(List.copyOf(evidence), List.copyOf(mapping), converted.toString());
    }
}
