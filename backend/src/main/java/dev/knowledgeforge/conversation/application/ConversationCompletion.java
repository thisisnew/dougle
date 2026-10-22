package dev.knowledgeforge.conversation.application;

import dev.knowledgeforge.library.application.LibraryException;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ConversationCompletion {
    private final ConversationRequestStore requests;
    private final ConversationStore conversations;
    public ConversationCompletion(ConversationRequestStore requests, ConversationStore conversations) {
        this.requests = requests; this.conversations = conversations;
    }
    public void attach(String workspaceId, String bundleId, String jobId) {
        requests.find(workspaceId, bundleId).ifPresent(request -> {
            var room = conversations.find(workspaceId, request.conversationId()).orElseThrow(() -> new LibraryException(409, "대화 보관함이 삭제되었습니다."));
            if (room.revision() != request.revision() || conversations.turns(workspaceId, room.id()).size() >= 100) {
                throw new LibraryException(409, "대화 보관함이 변경되었거나 한도에 도달했습니다.");
            }
            conversations.append(workspaceId, room.id(), jobId, room.revision() + 1, Instant.now().toString());
        });
    }
}
