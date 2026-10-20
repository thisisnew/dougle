package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.application.Collector;
import dev.knowledgeforge.sources.application.RemoteReader;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GithubCollector implements Collector {
    private final RemoteReader remote;
    private final GitRunner git;

    public GithubCollector(RemoteReader remote, GitRunner git) {
        this.remote = remote;
        this.git = git;
    }

    public String kind() { return "GITHUB"; }

    public void validate(String location) {
        if (!location.matches("https://github\\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+/?")
                || location.endsWith("/.") || location.endsWith("/..")) {
            throw new LibraryException(400, "공개 저장소의 https://github.com/owner/repository 주소를 입력하세요.");
        }
    }

    public CollectedSnapshot collect(String location) throws Exception {
        validate(location);
        String repository = location.substring("https://github.com/".length()).replaceAll("/$", "").replaceAll("\\.git$", "");
        var metadata = remote.read(URI.create("https://api.github.com/repos/" + repository + "/commits?per_page=1"));
        var commits = JsonMapper.builder().build().readTree(metadata.content());
        if (!commits.isArray() || commits.isEmpty()) throw new LibraryException(502, "GitHub commit을 확인할 수 없습니다.");
        String revision = commits.get(0).path("sha").asText();
        if (!revision.matches("[a-f0-9]{40}")) throw new LibraryException(502, "GitHub commit SHA가 올바르지 않습니다.");
        Path temporary = Files.createTempDirectory("forge-git-");
        try {
            git.run(temporary, 64 * 1024, "clone", "--quiet", "--no-checkout", "--filter=blob:none", "--depth=1",
                    "--single-branch", "--", "https://github.com/" + repository + ".git", "repository");
            Path checkout = temporary.resolve("repository");
            String actual = new String(git.run(checkout, 1024, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
            if (!actual.equals(revision)) throw new LibraryException(409, "수집 중 기본 브랜치가 변경됐습니다. 다시 동기화하세요.");
            return readSnapshot(checkout, revision);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public CollectedSnapshot readSnapshot(Path repository, String revision) throws Exception {
        String listing = new String(git.run(repository, 1024 * 1024, "ls-tree", "-rz", "-l", revision), StandardCharsets.UTF_8);
        List<CollectedSnapshot.File> files = new ArrayList<>();
        long total = 0;
        for (String item : listing.split("\u0000")) {
            if (item.isEmpty()) continue;
            String[] parts = item.split("\t", 2);
            String path = parts[1];
            if (List.of(path.split("/")).stream().anyMatch(CollectionLimits::excluded)) continue;
            CollectionLimits.path(path);
            String[] metadata = parts[0].strip().split("\\s+");
            if (!metadata[0].matches("100(644|755)") || !metadata[1].equals("blob")) {
                throw new LibraryException(400, "Git 심볼릭 링크·서브모듈은 수집하지 않습니다.");
            }
            long size = Long.parseLong(metadata[3]);
            total += size;
            if (size > CollectionLimits.MAX_FILE || total > CollectionLimits.MAX_TOTAL || files.size() >= CollectionLimits.MAX_FILES) {
                throw new LibraryException(413, "Git 파일 크기·총량·개수 제한을 초과했습니다.");
            }
            files.add(new CollectedSnapshot.File(path, git.run(repository, CollectionLimits.MAX_FILE, "cat-file", "blob", metadata[2])));
        }
        var snapshot = CollectionLimits.snapshot(files);
        return new CollectedSnapshot(revision, snapshot.files());
    }
}
