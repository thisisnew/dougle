package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.ConversationHistoryService;
import dev.knowledgeforge.conversation.application.ConversationRequestStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.search.application.AnswerContextProvider;
import dev.knowledgeforge.search.application.ContextPrompt;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class StoredAnswerContext implements AnswerContextProvider {
    private final ConversationRequestStore store;
    private final ConversationHistoryService histories;
    public StoredAnswerContext(ConversationRequestStore store, ConversationHistoryService histories) {
        this.store = store; this.histories = histories;
    }
    public Optional<Context> validated(String workspaceId, String bundleId) {
        return store.find(workspaceId, bundleId).map(request -> {
            try {
                var current = histories.read(workspaceId, request.conversationId(), request.revision(),
                        request.history().stream().map(item -> item.jobId()).toList());
                if (!current.equals(request.history())) throw new LibraryException(409, "저장한 이전 문맥이 변경되었습니다.");
            } catch (LibraryException exception) {
                throw new LibraryException(409, "이전 문맥을 사용할 수 없습니다. " + exception.getMessage());
            }
            return new Context(request.id(), request.conversationId(), request.revision(), request.provider(),
                    request.requestedModel(), ContextPrompt.prefix(request.history()), request.digest());
        });
    }
}
