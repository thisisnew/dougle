package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.Knowledge.*;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Entry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class KnowledgeService {
    private final KnowledgeStore store;
    private final LibraryStore library;

    public KnowledgeService(KnowledgeStore store, LibraryStore library) {
        this.store = store;
        this.library = library;
    }

    public List<Card> list(String workspaceId) {
        requireWorkspace(workspaceId);
        return store.list(workspaceId);
    }

    public Document get(String workspaceId, String id, Integer number) {
        Card card = store.card(workspaceId, id).orElseThrow(() -> new LibraryException(404, "지식을 찾을 수 없습니다."));
        Version version = store.version(workspaceId, id, number == null ? card.latestVersion() : number)
                .orElseThrow(() -> new LibraryException(404, "지식 버전을 찾을 수 없습니다."));
        return new Document(card, version);
    }

    public Document create(String workspaceId, String title, String markdown, List<Citation> citations) {
        requireWorkspace(workspaceId);
        validate(title, markdown);
        List<KnowledgeStore.Reference> references = capture(workspaceId, citations);
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        store.create(workspaceId, id, now);
        store.append(id, 1, title.strip(), markdown, now, references);
        return get(workspaceId, id, null);
    }

    public Document revise(String workspaceId, String id, int expectedVersion, String title,
                           String markdown, List<Citation> citations) {
        validate(title, markdown);
        Document previous = get(workspaceId, id, null);
        if (expectedVersion != previous.card().latestVersion()
                || !store.advance(workspaceId, id, expectedVersion)) {
            throw new LibraryException(409, "다른 편집이 저장되었습니다. 최신 버전을 다시 열어 주세요.");
        }
        List<KnowledgeStore.Reference> references = citations == null
                ? previous.version().evidence().stream().map(item -> new KnowledgeStore.Reference(
                        item.snapshotId(), item.startLine(), item.endLine())).toList()
                : capture(workspaceId, citations);
        store.append(id, expectedVersion + 1, title.strip(), markdown, Instant.now().toString(), references);
        return get(workspaceId, id, null);
    }

    public Document acceptGenerated(String workspaceId, String id, int expectedVersion, Document generated) {
        validate(generated.version().title(), generated.version().markdown());
        get(workspaceId, id, null);
        if (!store.advance(workspaceId, id, expectedVersion)) {
            throw new LibraryException(409, "다른 편집이 저장되었습니다. 최신 버전을 다시 열어 주세요.");
        }
        store.append(id, expectedVersion + 1, generated.version().title(), generated.version().markdown(),
                Instant.now().toString(), generated.version().evidence().stream()
                        .map(item -> new KnowledgeStore.Reference(item.snapshotId(), item.startLine(), item.endLine())).toList(), false);
        return get(workspaceId, id, null);
    }

    public void delete(String workspaceId, String id) {
        get(workspaceId, id, null);
        store.delete(workspaceId, id);
    }

    public Document createGenerated(Document source, String title, String markdown) {
        String workspaceId = source.card().workspaceId();
        Document current = get(workspaceId, source.card().id(), null);
        if (!current.equals(source)) {
            throw new LibraryException(409, "생성 중 지식 또는 근거 상태가 변경되었습니다. 다시 확인하고 승인하세요.");
        }
        validate(title, markdown);
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        store.create(workspaceId, id, now);
        store.append(id, 1, title.strip(), markdown, now, source.version().evidence().stream()
                .map(item -> new KnowledgeStore.Reference(item.snapshotId(), item.startLine(), item.endLine())).toList(), false);
        return get(workspaceId, id, null);
    }

    public byte[] snapshotContent(String workspaceId, String id, String snapshotId) {
        return store.snapshotContent(workspaceId, id, snapshotId)
                .orElseThrow(() -> new LibraryException(404, "근거 원본을 찾을 수 없습니다."));
    }

    public record EvidenceLocation(Entry entry, List<Entry> ancestors, String freshness) {}

    public EvidenceLocation locateEvidence(String workspaceId, String id, int number, String snapshotId) {
        Evidence evidence = get(workspaceId, id, number).version().evidence().stream()
                .filter(item -> item.snapshotId().equals(snapshotId)).findFirst()
                .orElseThrow(() -> new LibraryException(404, "이 지식 버전의 근거를 찾을 수 없습니다."));
        var current = store.currentMaterial(workspaceId, evidence.entryId(), evidence.sourceId(), evidence.path())
                .orElseThrow(() -> new LibraryException(404, "현재 원본이 없습니다. 당시 근거 원본 다운로드를 이용하세요."));
        Entry entry = library.entry(workspaceId, current.entryId())
                .orElseThrow(() -> new LibraryException(404, "현재 원본을 찾을 수 없습니다."));
        List<Entry> ancestors = new ArrayList<>();
        var visited = new HashSet<String>();
        String parentId = entry.parentId();
        while (parentId != null) {
            if (!visited.add(parentId)) throw new LibraryException(409, "원본 폴더 경로에 순환 참조가 있습니다.");
            Entry parent = library.entry(workspaceId, parentId)
                    .orElseThrow(() -> new LibraryException(409, "원본 폴더 경로가 변경되었습니다. 다시 확인하세요."));
            if (!"FOLDER".equals(parent.kind()) || !"ORIGINAL".equals(parent.area())) {
                throw new LibraryException(409, "원본 폴더 경로가 올바르지 않습니다.");
            }
            ancestors.add(parent);
            parentId = parent.parentId();
        }
        return new EvidenceLocation(entry, List.copyOf(ancestors.reversed()), evidence.freshness());
    }

    private List<KnowledgeStore.Reference> capture(String workspaceId, List<Citation> citations) {
        if (citations == null || citations.isEmpty() || citations.size() > 10) {
            throw new LibraryException(400, "근거는 1~10개 선택하세요.");
        }
        List<KnowledgeStore.Reference> references = new ArrayList<>();
        long bytes = 0;
        for (Citation citation : citations) {
            if (citation == null || citation.entryId() == null) throw new LibraryException(400, "근거 자료를 선택하세요.");
            var material = store.material(workspaceId, citation.entryId())
                    .orElseThrow(() -> new LibraryException(404, "근거 파일을 찾을 수 없습니다."));
            if (!List.of("READY", "PARTIAL").contains(material.analysisStatus()) || material.text().isBlank()) {
                throw new LibraryException(400, "추출된 텍스트가 있는 파일을 선택하세요.");
            }
            String[] lines = material.text().split("\n", -1);
            if (citation.startLine() < 1 || citation.endLine() < citation.startLine()
                    || citation.endLine() > lines.length || citation.endLine() - citation.startLine() >= 500) {
                throw new LibraryException(400, "근거는 추출 텍스트의 유효한 1~500행 범위여야 합니다.");
            }
            bytes += material.content().length;
            if (bytes > 50L * 1024 * 1024) throw new LibraryException(413, "근거 원본 합계는 50 MiB 이하여야 합니다.");
            references.add(new KnowledgeStore.Reference(store.capture(workspaceId, material), citation.startLine(), citation.endLine()));
        }
        return references;
    }

    private void validate(String title, String markdown) {
        if (title == null || title.isBlank() || title.strip().length() > 120
                || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new LibraryException(400, "지식 제목은 1~120자로 입력하세요.");
        }
        if (markdown == null || markdown.isBlank() || markdown.length() > 100_000) {
            throw new LibraryException(400, "지식 본문은 1~100,000자로 입력하세요.");
        }
    }

    private void requireWorkspace(String workspaceId) {
        library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
    }
}
