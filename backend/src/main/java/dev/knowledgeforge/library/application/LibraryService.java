package dev.knowledgeforge.library.application;

import dev.knowledgeforge.library.domain.Entry;
import dev.knowledgeforge.library.domain.Workspace;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LibraryService {
    private final LibraryStore store;

    public LibraryService(LibraryStore store) {
        this.store = store;
    }

    public record Session(List<Workspace> workspaces, String activeWorkspaceId) {}
    public record Preview(String text, boolean supported, String reason) {}

    public Session session() {
        return new Session(store.workspaces(), store.activeWorkspace());
    }

    public Workspace createWorkspace(String name) {
        Workspace workspace = new Workspace(UUID.randomUUID().toString(), validName(name), Instant.now().toString());
        store.createWorkspace(workspace);
        store.activate(workspace.id());
        return workspace;
    }

    public void activate(String id) {
        requireWorkspace(id);
        store.activate(id);
    }

    public void deleteWorkspace(String id) {
        requireWorkspace(id);
        boolean active = id.equals(store.activeWorkspace());
        store.deleteWorkspace(id);
        if (active) {
            store.activate(store.workspaces().stream().map(Workspace::id).findFirst().orElse(null));
        }
    }

    public List<Entry> entries(String workspaceId, String area, String parentId) {
        requireWorkspace(workspaceId);
        if (!List.of("ORIGINAL", "KNOWLEDGE").contains(area)) {
            throw new LibraryException(400, "올바른 자료 영역을 선택하세요.");
        }
        requireParent(workspaceId, area, parentId);
        return store.entries(workspaceId, area, parentId);
    }

    public Entry createFolder(String workspaceId, String parentId, String name) {
        return createEntry(workspaceId, parentId, name, "FOLDER", new byte[0]);
    }

    public Entry upload(String workspaceId, String parentId, String name, byte[] content) {
        if (content.length > 10 * 1024 * 1024) {
            throw new LibraryException(413, "파일은 10 MiB 이하여야 합니다.");
        }
        return createEntry(workspaceId, parentId, name, "FILE", content);
    }

    private Entry createEntry(String workspaceId, String parentId, String name, String kind, byte[] content) {
        requireWorkspace(workspaceId);
        requireParent(workspaceId, "ORIGINAL", parentId);
        Entry entry = new Entry(UUID.randomUUID().toString(), workspaceId, parentId, validName(name),
                kind, "ORIGINAL", content.length, Instant.now().toString());
        store.createEntry(entry, content);
        return entry;
    }

    public Entry requireEntry(String workspaceId, String id) {
        return store.entry(workspaceId, id).orElseThrow(() -> new LibraryException(404, "자료를 찾을 수 없습니다."));
    }

    public Preview preview(String workspaceId, String id) {
        Entry entry = requireEntry(workspaceId, id);
        if (!entry.kind().equals("FILE")) {
            throw new LibraryException(400, "폴더는 미리 볼 수 없습니다.");
        }
        if (entry.size() > 1024 * 1024) {
            return new Preview(null, false, "미리보기는 1 MiB 이하의 UTF-8 텍스트만 지원합니다.");
        }
        byte[] content = store.content(id);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            if (text.codePoints().anyMatch(value -> Character.isISOControl(value)
                    && value != '\n' && value != '\r' && value != '\t')) {
                return new Preview(null, false, "바이너리 파일입니다. 원본 다운로드를 이용하세요.");
            }
            return new Preview(text, true, null);
        } catch (CharacterCodingException exception) {
            return new Preview(null, false, "UTF-8 텍스트가 아닙니다. 원본 다운로드를 이용하세요.");
        }
    }

    public byte[] content(String workspaceId, String id) {
        if (!requireEntry(workspaceId, id).kind().equals("FILE")) {
            throw new LibraryException(400, "폴더는 다운로드할 수 없습니다.");
        }
        return store.content(id);
    }

    public void deleteEntry(String workspaceId, String id) {
        requireEntry(workspaceId, id);
        store.deleteEntry(workspaceId, id);
    }

    private void requireWorkspace(String id) {
        store.workspace(id).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
    }

    private void requireParent(String workspaceId, String area, String parentId) {
        if (parentId == null) {
            return;
        }
        Entry parent = requireEntry(workspaceId, parentId);
        if (!parent.kind().equals("FOLDER") || !parent.area().equals(area)) {
            throw new LibraryException(400, "같은 영역의 폴더를 선택하세요.");
        }
    }

    private String validName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120
                || name.matches(".*[\\\\/].*") || name.codePoints().anyMatch(Character::isISOControl)
                || List.of(".", "..").contains(name.strip())) {
            throw new LibraryException(400, "이름은 경로 구분자 없이 1~120자로 입력하세요.");
        }
        return name.strip();
    }
}
