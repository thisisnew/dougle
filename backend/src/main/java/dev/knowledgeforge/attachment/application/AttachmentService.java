package dev.knowledgeforge.attachment.application;

import dev.knowledgeforge.attachment.domain.QuestionAttachment;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.domain.ContentDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    private final AttachmentStore store;
    private final AttachmentReader reader;
    private final LibraryStore library;
    public AttachmentService(AttachmentStore store, AttachmentReader reader, LibraryStore library) {
        this.store = store; this.reader = reader; this.library = library;
    }
    @Transactional
    public QuestionAttachment.Summary upload(String workspaceId, String name, byte[] content) {
        library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
        if (name == null || name.isBlank() || name.length() > 180 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl)) throw new LibraryException(400, "파일 이름을 확인해 주세요.");
        if (content.length == 0) throw new LibraryException(400, "빈 파일은 첨부할 수 없습니다.");
        if (content.length > MAX_FILE_BYTES) throw new LibraryException(413, "첨부 파일은 각각 5 MiB 이하여야 합니다.");
        store.expireUnbound(Instant.now().minus(1, ChronoUnit.DAYS).toString());
        if (store.pendingCount(workspaceId) >= 20 || store.usedBytes(workspaceId) + content.length > 64L * 1024 * 1024) {
            throw new LibraryException(409, "첨부 보관 공간이 부족합니다. 사용하지 않는 질문이나 첨부를 삭제해 주세요.");
        }
        var parsed = reader.read(name, content);
        if (parsed.content().length > MAX_FILE_BYTES || store.usedBytes(workspaceId) + parsed.content().length > 64L * 1024 * 1024) {
            throw new LibraryException(413, "이미지 처리 후 용량이 큽니다. 크기를 줄여 다시 첨부해 주세요.");
        }
        var item = new QuestionAttachment(UUID.randomUUID().toString(), workspaceId, null, name, parsed.mediaType(),
                parsed.content(), ContentDigest.of(parsed.content()), parsed.text(), parsed.truncated(), Instant.now().toString());
        store.save(item);
        return item.summary();
    }
    public QuestionAttachment get(String workspaceId, String id) {
        var item = store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "첨부 파일을 찾을 수 없습니다. 다시 첨부해 주세요."));
        if (!ContentDigest.of(item.content()).equals(item.contentHash())) throw new LibraryException(409, "첨부 파일이 변경되었습니다. 다시 첨부해 주세요.");
        return item;
    }
    public List<QuestionAttachment> pending(String workspaceId, List<String> ids) {
        if (ids == null || ids.size() > 4 || ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            throw new LibraryException(400, "첨부는 중복 없이 최대 4개까지 가능합니다.");
        }
        var items = ids.stream().map(id -> get(workspaceId, id)).toList();
        if (items.stream().anyMatch(item -> item.bundleId() != null)) throw new LibraryException(409, "이미 질문에 연결된 첨부입니다. 새 질문에는 다시 첨부해 주세요.");
        if (items.stream().mapToLong(item -> item.content().length).sum() > MAX_TOTAL_BYTES) throw new LibraryException(413, "질문 하나의 첨부는 합계 10 MiB 이하여야 합니다.");
        return items;
    }
    public void bind(String workspaceId, List<String> ids, String bundleId) {
        for (String id : ids) if (!store.bind(workspaceId, id, bundleId)) throw new LibraryException(409, "첨부 상태가 변경되었습니다. 다시 확인해 주세요.");
    }
    @Transactional
    public void delete(String workspaceId, String id) {
        if (!store.deleteUnbound(workspaceId, id) && store.find(workspaceId, id).isPresent()) {
            throw new LibraryException(409, "질문에 연결된 첨부는 질문과 함께 삭제됩니다.");
        }
    }
    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void expire() { store.expireUnbound(Instant.now().minus(1, ChronoUnit.DAYS).toString()); }
}
