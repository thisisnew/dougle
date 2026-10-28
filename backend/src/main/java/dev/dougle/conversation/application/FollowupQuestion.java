package dev.dougle.conversation.application;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FollowupQuestion {
    private static final Pattern TOPIC_SWITCH = Pattern.compile("(?:^| )(?:instead|rather|말고|대신|됐고|이제)(?: |$)");
    private static final Pattern REFERENT = Pattern.compile(
            "^(?:(?:거기|여기|그곳)(?:에|에서|는|도)?|(?:첫|두|세|네) 번째 (?:것|것은|것을|것이))(?= |$)");
    private static final Set<String> WORDS = Set.of(
            "which", "aspect", "could", "you", "elaborate", "please", "explain", "more", "that", "this",
            "it", "those", "these", "again", "why", "how", "so", "then", "what", "about", "the", "same",
            "subject", "topic", "summarize", "summary", "briefly", "in", "one", "sentence", "keep", "focus",
            "on", "should", "i", "look", "at", "detail", "details", "can", "would", "me", "tell",
            "a", "bit", "little", "further", "expand", "clarify", "rephrase", "simpler", "terms", "for",
            "put", "another", "way", "say", "shorter", "version", "point", "part", "break", "down",
            "previous", "answer", "your", "continue", "go", "into", "does", "mean",
            "어느", "어느쪽을", "쪽을", "중점적으로", "보면", "돼", "보면돼", "그거", "그건", "그걸",
            "그것", "이것", "좀", "더", "자세히", "설명해", "알려줘", "줘", "주세요", "다시", "왜",
            "앞서", "설명한", "대상을", "유지하고", "핵심만", "한", "문장으로", "정리해", "확인",
            "요약해", "계속", "이어서", "쉽게", "말해줘", "부분", "부분을", "어떤", "그", "이",
            "말을", "말로", "풀어서", "설명해줘", "설명해주세요", "설명해줄래", "줄래", "줄", "수",
            "있어", "있나요", "조금", "간단히", "짧게", "요약해줘", "정리해줘", "무슨", "뜻이야",
            "뜻인가요", "그게", "이게", "앞의", "이전", "답변을", "그럼", "자세하게", "풀어줘");

    private FollowupQuestion() {}

    static boolean referential(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip()
                .replaceFirst(" 확인 [0-9]+$", "");
        if (normalized.isEmpty()) return false;
        if (TOPIC_SWITCH.matcher(normalized).find()) return false;
        if (REFERENT.matcher(normalized).find()) return true;
        boolean word = false;
        for (String token : normalized.split(" +")) {
            if (!WORDS.contains(token)) return false;
            word = true;
        }
        return word;
    }
}
