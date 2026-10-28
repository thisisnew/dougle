package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.application.Collector;
import dev.dougle.sources.application.RemoteReader;
import dev.dougle.sources.domain.CollectedSnapshot;
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
    private java.util.function.Supplier<String> credential = () -> null;

    @org.springframework.beans.factory.annotation.Autowired
    public GithubCollector(RemoteReader remote, GitRunner git, dev.dougle.plugins.adapter.PluginVault vault) {
        this(remote, git);
        credential = () -> vault.get("github");
    }

    public GithubCollector(RemoteReader remote, GitRunner git) {
        this.remote = remote;
        this.git = git;
    }

    public String kind() { return "GITHUB"; }
    public boolean usesPreviousSnapshot() { return true; }

    public void validate(String location) {
        if (!location.matches("https://github\\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+/?")
                || location.endsWith("/.") || location.endsWith("/..")) {
            throw new LibraryException(400, "https://github.com/owner/repository 주소를 입력하세요.");
        }
    }

    public CollectedSnapshot collect(String location) throws Exception {
        return collect(location, null);
    }

    public CollectedSnapshot collect(String location, CollectedSnapshot previous) throws Exception {
        validate(location);
        String repository = location.substring("https://github.com/".length()).replaceAll("/$", "").replaceAll("\\.git$", "");
        String token = credential.get();
        URI endpoint = URI.create("https://api.github.com/repos/" + repository + "/commits?per_page=1");
        var metadata = token == null ? remote.read(endpoint) : remote.readGithub(endpoint, token);
        var commits = JsonMapper.builder().build().readTree(metadata.content());
        if (!commits.isArray() || commits.isEmpty()) throw new LibraryException(502, "GitHub commit을 확인할 수 없습니다.");
        String revision = commits.get(0).path("sha").asText();
        if (!revision.matches("[a-f0-9]{40}")) throw new LibraryException(502, "GitHub commit SHA가 올바르지 않습니다.");
        if (previous != null && previous.failures().isEmpty() && revision.equals(previous.revision())) return previous;
        var authenticatedGit = token == null ? git : git.authenticated(token);
        Path temporary = Files.createTempDirectory("forge-git-");
        try {
            authenticatedGit.run(temporary, 64 * 1024, "clone", "--quiet", "--no-checkout", "--filter=blob:none", "--depth=1",
                    "--single-branch", "--", "https://github.com/" + repository + ".git", "repository");
            Path checkout = temporary.resolve("repository");
            String actual = new String(authenticatedGit.run(checkout, 1024, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
            if (!actual.equals(revision)) throw new LibraryException(409, "수집 중 기본 브랜치가 변경됐습니다. 다시 동기화하세요.");
            return new GithubCollector(remote, authenticatedGit).readSnapshot(checkout, revision, previous);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public CollectedSnapshot readSnapshot(Path repository, String revision) throws Exception {
        return readSnapshot(repository, revision, null);
    }

    public CollectedSnapshot readSnapshot(Path repository, String revision, CollectedSnapshot previous) throws Exception {
        if (!revision.matches("[a-f0-9]{40}")) throw new LibraryException(400, "Git revision이 올바르지 않습니다.");
        var cached = new java.util.HashMap<String, CollectedSnapshot.File>();
        if (previous != null) {
            for (var file : CollectionLimits.snapshot(previous.files()).files()) cached.put(blobHash(file), file);
        }
        String listing = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(
                git.run(repository, 8 * 1024 * 1024, "ls-tree", "-rz", revision))).toString();
        List<CollectedSnapshot.File> files = new ArrayList<>();
        var failures = new java.util.HashMap<String, String>();
        long total = 0;
        try {
        for (String item : listing.split("\u0000")) {
            if (item.isEmpty()) continue;
            String[] parts = item.split("\t", 2);
            if (parts.length != 2) throw new LibraryException(502, "Git tree 형식이 올바르지 않습니다.");
            String path = parts[1];
            if (List.of(path.split("/")).stream().anyMatch(CollectionLimits::excluded)) continue;
            CollectionLimits.path(path);
            String[] metadata = parts[0].strip().split("\\s+");
            if (metadata.length == 3 && metadata[0].equals("160000") && metadata[1].equals("commit")
                    && metadata[2].matches("[a-f0-9]{40}")) {
                failures.put(path, "서브모듈은 별도 GitHub 저장소로 연결하세요.");
                continue;
            }
            if (metadata.length != 3 || !metadata[0].matches("100(644|755)") || !metadata[1].equals("blob")
                    || !metadata[2].matches("[a-f0-9]{40}")) {
                throw new LibraryException(400, "Git 심볼릭 링크·서브모듈은 수집하지 않습니다.");
            }
            dev.dougle.ingestion.application.ProcessingProgress.report("READING", path, files.size(), 0);
            var content = cached.get(metadata[2]);
            long size = content == null ? Long.parseLong(new String(git.run(repository, 32, "cat-file", "-s", metadata[2]),
                    StandardCharsets.UTF_8).strip()) : content.size();
            if (size < 0 || size > CollectionLimits.MAX_DOCUMENT) {
                failures.put(path, "256 MiB가 넘는 파일은 나눠 등록하세요.");
                continue;
            }
            total += size;
            if (total > CollectionLimits.MAX_SNAPSHOT || files.size() >= CollectionLimits.MAX_SNAPSHOT_FILES) {
                throw new LibraryException(413, "저장소를 나눠 등록하세요. 한 자료당 20,000개 파일·8 GiB까지 처리합니다.");
            }
            if (content == null) {
                try {
                    content = git.readBlob(repository, path, metadata[2], size);
                } catch (java.io.InterruptedIOException exception) {
                    throw exception;
                } catch (java.io.IOException exception) {
                    failures.put(path, "파일 수집 실패 · 저장소 접근 권한과 네트워크를 확인하고 다시 동기화하세요.");
                    continue;
                }
                if (content.size() != size || !blobHash(content).equals(metadata[2])) {
                    content.close();
                    throw new LibraryException(502, "Git blob 크기 또는 해시가 일치하지 않습니다.");
                }
                cached.put(metadata[2], content);
            }
            if (content.size() <= 1024 && new String(content.content(), StandardCharsets.UTF_8)
                    .matches("(?s)version https://git-lfs\\.github\\.com/spec/v1\\r?\\n.*\\boid sha256:[a-f0-9]{64}\\r?\\n.*\\bsize [0-9]+\\s*")) {
                failures.put(path, "Git LFS 원본이 필요합니다. 내려받은 원본 파일을 추가하세요.");
                cached.remove(metadata[2]);
                if (previous == null || !previous.files().contains(content)) content.close();
                continue;
            }
            if (!content.path().equals(path)) {
                try (var input = content.open()) {
                    content = CollectedSnapshot.File.copy(path, input, CollectionLimits.MAX_DOCUMENT);
                }
            }
            files.add(content);
        }
        var snapshot = CollectionLimits.snapshot(files);
        return new CollectedSnapshot(revision, snapshot.files(), null, failures);
        } catch (Exception exception) {
            files.stream().filter(file -> previous == null || !previous.files().contains(file)).forEach(CollectedSnapshot.File::close);
            throw exception;
        }
    }

    private static String blobHash(CollectedSnapshot.File file) throws java.security.NoSuchAlgorithmException, java.io.IOException {
        var digest = java.security.MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + file.size() + "\u0000").getBytes(StandardCharsets.US_ASCII));
        try (var input = file.open()) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

}
