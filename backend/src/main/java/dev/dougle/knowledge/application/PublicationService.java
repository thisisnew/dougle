package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.Knowledge.Document;
import dev.dougle.knowledge.domain.Publication.*;
import dev.dougle.library.application.LibraryException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PublicationService {
    private static final Pattern CITATION = Pattern.compile("\\[E([^]\\r\\n]*)]");
    private final KnowledgeService knowledge;
    private final PublicationStore store;

    public PublicationService(KnowledgeService knowledge, PublicationStore store) {
        this.knowledge = knowledge;
        this.store = store;
    }

    public State state(String workspaceId, String id) {
        Document current = knowledge.get(workspaceId, id, null);
        Release publication = store.publication(id).orElse(null);
        return new State(current.card().latestVersion(), store.review(id, current.card().latestVersion()).orElse(null),
                publication, publication == null ? List.of() : knowledge.get(workspaceId, id, publication.version()).version().evidence());
    }

    public State review(String workspaceId, String id, int expectedVersion, String reviewer, String note,
                        boolean claimsSupported, boolean uncertaintyRemoved) {
        Document document = eligible(workspaceId, id, expectedVersion);
        if (!claimsSupported || !uncertaintyRemoved) {
            throw new LibraryException(400, "제목·모든 주장과 근거를 직접 대조하고, 근거 없는 추론을 제거했는지 확인하세요.");
        }
        if (reviewer == null || reviewer.isBlank() || reviewer.length() > 120
                || reviewer.codePoints().anyMatch(Character::isISOControl)
                || note == null || note.isBlank() || note.length() > 4000) {
            throw new LibraryException(400, "검토자(1~120자)와 검토 기록(1~4,000자)을 입력하세요.");
        }
        if (store.review(id, document.version().number()).isPresent()) {
            throw new LibraryException(409, "이미 검토된 버전입니다. 수정하려면 새 버전을 저장하세요.");
        }
        store.review(id, new Review(expectedVersion, reviewer.strip(), note.strip(), Instant.now().toString()));
        return state(workspaceId, id);
    }

    public State publish(String workspaceId, String id, int expectedVersion, int expectedPublicationRevision) {
        eligible(workspaceId, id, expectedVersion);
        if (store.review(id, expectedVersion).isEmpty()) {
            throw new LibraryException(409, "이 버전의 사람 검토가 먼저 필요합니다.");
        }
        if (expectedPublicationRevision < 0 || !store.publish(id, expectedVersion, expectedPublicationRevision, Instant.now().toString())) {
            throw new LibraryException(409, "게시 상태가 변경되었거나 이미 게시된 버전입니다. 새로고침하세요.");
        }
        return state(workspaceId, id);
    }

    public Document published(String workspaceId, String id) {
        knowledge.get(workspaceId, id, null);
        Release publication = store.publication(id).orElseThrow(() -> new LibraryException(404, "게시본이 없습니다."));
        return knowledge.get(workspaceId, id, publication.version());
    }

    private Document eligible(String workspaceId, String id, int expectedVersion) {
        Document document = knowledge.get(workspaceId, id, null);
        if (document.card().latestVersion() != expectedVersion) {
            throw new LibraryException(409, "초안 버전이 변경되었습니다. 최신 버전을 다시 검토하세요.");
        }
        var evidence = document.version().evidence();
        if (evidence.isEmpty() || evidence.stream().anyMatch(item -> !"CURRENT".equals(item.freshness()))) {
            throw new LibraryException(409, "근거가 변경되거나 삭제되었습니다. 새 근거로 새 버전을 작성하세요.");
        }
        var matcher = CITATION.matcher(document.version().markdown());
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String reference = matcher.group(1);
            if (!reference.matches("[1-9][0-9]?") || Integer.parseInt(reference) > evidence.size()) {
                throw new LibraryException(400, "본문의 근거 번호가 유효하지 않습니다.");
            }
        }
        if (!found) throw new LibraryException(400, "본문에 [E1] 형식의 근거 인용을 추가하세요. 인용 번호 검사는 사실 검증이 아닙니다.");
        return document;
    }
}
