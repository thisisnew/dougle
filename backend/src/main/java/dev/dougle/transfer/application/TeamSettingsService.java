package dev.dougle.transfer.application;

import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.sources.application.SourceStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TeamSettingsService {
    public static final int MAX_SOURCES = 100;
    private static final Set<String> KINDS = Set.of("LOCAL_FOLDER", "URL", "GITHUB", "MYSQL");
    private final LibraryStore library;
    private final SourceStore sources;

    public TeamSettingsService(LibraryStore library, SourceStore sources) {
        this.library = library;
        this.sources = sources;
    }

    public record Descriptor(String name, String kind, String location) {}
    public record Settings(int version, String workspaceName, int pollIntervalSeconds, List<Descriptor> sources) {}
    public record EntryPreview(Descriptor source, boolean needsLocation, boolean needsReconnection, boolean nameConflict) {}
    public record Preview(Settings settings, boolean workspaceNameConflict, boolean needsWorkspaceRename, List<EntryPreview> entries,
                          boolean applySupported, String limitation) {}

    public Settings exportSettings(String workspaceId) {
        var workspace = library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
        var descriptors = new ArrayList<Descriptor>();
        for (var source : sources.list(workspaceId)) {
            if (source.kind() == null || !KINDS.contains(source.kind())) throw invalid("지원하지 않는 자료 종류가 있어 내보낼 수 없습니다.");
            descriptors.add(new Descriptor(source.name(), source.kind(), null));
        }
        var settings = new Settings(1, workspace.name(), sources.workspacePolling(workspaceId).intervalSeconds(), List.copyOf(descriptors));
        validate(settings);
        return settings;
    }

    public Preview preview(Settings settings) {
        validate(settings);
        boolean conflict = library.workspaces().stream().anyMatch(workspace -> workspace.name().equalsIgnoreCase(settings.workspaceName()));
        var seen = new HashSet<String>();
        var entries = settings.sources().stream().map(source -> new EntryPreview(source,
                source.location() == null,
                !source.kind().equals("LOCAL_FOLDER"),
                !seen.add(source.name().toLowerCase(java.util.Locale.ROOT)))).toList();
        return new Preview(settings, conflict, settings.workspaceName().length() > 20, entries, true,
                "이 미리보기는 설정을 변경하지 않습니다. 새 Workspace 이름과 자료 위치를 지정하고 가져오기 계획을 확인한 뒤 명시적으로 적용할 수 있습니다. "
                + "URL은 경로에도 비밀정보가 포함될 수 있어 전체를 제외합니다. 폴더·URL·DB 위치는 다시 지정하고 원격 자료는 다시 연결해야 합니다. "
                + "기존 Workspace 이름은 최대 120자까지 보존하지만 새로 만들 때는 20자 이내로 변경해야 합니다. "
                + "적용 시 자동 갱신은 꺼져 있으며 동기화·AI를 실행하지 않습니다. MySQL은 생성하지 않고 플러그인에서 다시 연결해야 합니다. 공유 전 표시 이름에 비밀정보가 없는지 확인하세요.");
    }

    public static void validate(Settings settings) {
        if (settings == null || settings.version() != 1) throw invalid("지원하지 않는 팀 설정 버전입니다.");
        name(settings.workspaceName(), 120);
        if (settings.pollIntervalSeconds() < 60 || settings.pollIntervalSeconds() > 86400) throw invalid("갱신 주기는 60~86400초로 지정하세요.");
        if (settings.sources() == null || settings.sources().size() > MAX_SOURCES) throw invalid("자료 목록을 포함하고 자료 수는 100개 이내로 지정하세요.");
        for (var source : settings.sources()) {
            if (source == null || source.kind() == null || !KINDS.contains(source.kind())) throw invalid("지원하지 않는 자료 설정입니다.");
            name(source.name(), 120);
            if (source.location() != null) {
                throw invalid("팀 설정에는 URL·폴더·DB 위치를 포함할 수 없습니다. 위치는 비워 두고 다시 지정하세요.");
            }
        }
    }

    private static void name(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || !value.equals(value.strip())
                || value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("이름은 앞뒤 공백과 경로 구분자 없이 1~" + maximum + "자로 입력하세요.");
    }

    private static LibraryException invalid(String message) { return new LibraryException(400, message); }
}
