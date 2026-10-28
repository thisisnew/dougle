package dev.dougle.conversation.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class FollowupQuestionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Can you put that another way?",
            "Would you break that down for me?",
            "Please go into a little more detail.",
            "What does that mean?",
            "A shorter version please!",
            "그 부분을 조금 더 쉽게 설명해줄래?",
            "이전 답변을 짧게 정리해줘.",
            "그게 무슨 뜻이야?",
            "어느 부분?",
            "어느 쪽을 중점적으로 보면 돼?",
            "앞서 설명한 대상을 유지하고 핵심만 한 문장으로 정리해 줘. 확인 20",
            "거기에 저장되는 시점은?",
            "두 번째 것은 자동 갱신돼?",
            "그곳에서 측정하는 간격은?",
            "그럼 더 설명해줘",
            "그 부분을 좀 더 자세하게 풀어줘"
    })
    void recognizesGenericAndReferentialContinuations(String question) {
        assertThat(FollowupQuestion.referential(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What about Quasar?",
            "Explain Quasar instead",
            "이번에는 Quasar 설명해줘",
            "What about 2049?",
            "NightState",
            "Strategy",
            "이제 싱글턴 이야기하자",
            "거기 말고 Quasar 설명해줘",
            "두 번째 것은 됐고 Quasar 설명해줘",
            "그럼 Quasar 더 설명해줘",
            "그 부분을 좀 더 자세하게 풀어줘 대신 Quasar 설명해줘"
    })
    void rejectsExplicitTopicsAndSwitches(String question) {
        assertThat(FollowupQuestion.referential(question)).isFalse();
    }
}
