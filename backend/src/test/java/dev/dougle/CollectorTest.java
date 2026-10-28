package dev.dougle;

import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.adapter.*;
import dev.dougle.sources.application.RemoteReader;
import dev.dougle.sources.domain.CollectedSnapshot;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectorTest {
    @TempDir Path directory;

    @Test
    void localRegistrationRejectsFilesMissingFoldersAndLinksBeforeSync() throws Exception {
        var collector = new LocalFolderCollector();
        Path file = Files.writeString(directory.resolve("synthetic.pdf"), "synthetic");
        assertThatThrownBy(() -> collector.validate(file.toString())).isInstanceOf(LibraryException.class).hasMessageContaining("자료 추가 → 파일");
        assertThatThrownBy(() -> collector.validate(directory.resolve("missing").toString())).isInstanceOf(LibraryException.class);
        Path link = Files.createSymbolicLink(directory.resolve("folder-link"), directory);
        assertThatThrownBy(() -> collector.validate(link.toString())).isInstanceOf(LibraryException.class);
        assertThatCode(() -> collector.validate(directory.toString())).doesNotThrowAnyException();
    }

    @Test
    void urlCannotReuse304WithoutTheMatchingStoredFinalUri() throws Exception {
        RemoteReader remote = mock(RemoteReader.class);
        URI uri = URI.create("https://example.org/document");
        when(remote.read(uri)).thenReturn(new RemoteReader.Response(uri, null, new byte[0], true, null));
        UrlCollector collector = new UrlCollector(remote, mock(dev.dougle.plugins.adapter.NotionClient.class), mock(dev.dougle.plugins.adapter.PluginConnections.class), mock(dev.dougle.plugins.adapter.BrowserPluginReader.class));
        assertThatThrownBy(() -> collector.collect(uri.toString())).isInstanceOf(LibraryException.class).hasMessageContaining("304");
        var validators = new dev.dougle.sources.domain.HttpValidators(uri.toString(), "\"synthetic\"", null);
        var previous = new CollectedSnapshot("previous", List.of(new CollectedSnapshot.File("document", new byte[] {1})), validators);
        when(remote.read(uri, validators)).thenReturn(new RemoteReader.Response(URI.create("https://example.net/other"), null, new byte[0], true, validators));
        assertThatThrownBy(() -> collector.collect(uri.toString(), previous)).isInstanceOf(LibraryException.class).hasMessageContaining("304");
    }

    @Test
    void localCollectorRejectsLinksAndSpecialPaths() throws Exception {
        var collector = new LocalFolderCollector();
        assertThatThrownBy(() -> collector.validate("../relative")).isInstanceOf(LibraryException.class);
        Path root = Files.createDirectory(directory.resolve("root"));
        Files.writeString(directory.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(root.resolve("escape"), directory.resolve("outside.txt"));
        try (var snapshot = collector.collect(root.toString())) {
            assertThat(snapshot.files()).isEmpty();
            assertThat(snapshot.failures()).containsKey("escape");
        }
        Files.createSymbolicLink(directory.resolve("root-link"), root);
        assertThatThrownBy(() -> collector.collect(directory.resolve("root-link").toString())).isInstanceOf(LibraryException.class);
    }

    @Test
    void collectingAndDiscardingSnapshotsNeverChangesSourceFiles() throws Exception {
        Path root = Files.createDirectory(directory.resolve("originals"));
        Path source = Files.writeString(root.resolve("업무 원본.txt"), "synthetic immutable original");
        var modified = Files.getLastModifiedTime(source);
        byte[] original = Files.readAllBytes(source);
        var collector = new LocalFolderCollector();
        try (var snapshot = collector.collect(root.toString())) {
            assertThat(snapshot.files()).hasSize(1);
            assertThat(snapshot.files().getFirst().content()).isEqualTo(original);
        }
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(source)).isEqualTo(modified);
        try (var files = Files.list(root)) {
            assertThat(files.toList()).containsExactly(source);
        }
        Files.createSymbolicLink(root.resolve("rejected-link"), source);
        try (var snapshot = collector.collect(root.toString())) {
            assertThat(snapshot.files()).hasSize(1);
            assertThat(snapshot.failures()).containsKey("rejected-link");
        }
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(source)).isEqualTo(modified);
        assertThat(Files.isSymbolicLink(root.resolve("rejected-link"))).isTrue();
    }

    @Test
    void streamsMoreThanLegacyFileCountAndSizeWhileKeepingPathProtection() throws Exception {
        for (int index = 0; index <= 500; index++) Files.writeString(directory.resolve("file-" + index), "x");
        try (var snapshot = new LocalFolderCollector().collect(directory.toString())) {
            assertThat(snapshot.files()).hasSize(501);
        }
        assertThatThrownBy(() -> CollectionLimits.path("../secret")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> CollectionLimits.path("a/".repeat(17) + "file")).isInstanceOf(LibraryException.class);
        try (var snapshot = CollectionLimits.snapshot(List.of(
                new CollectedSnapshot.File("large.bin", new byte[CollectionLimits.MAX_FILE + 1])))) {
            assertThat(snapshot.files().getFirst().size()).isEqualTo(CollectionLimits.MAX_FILE + 1);
        }
    }

    @Test
    void revisionIsDeterministicAcrossOrderingButChangesWithPathOrBytes() {
        var first = new CollectedSnapshot.File("a.txt", new byte[] {1});
        var second = new CollectedSnapshot.File("b.txt", new byte[] {2});
        assertThat(CollectionLimits.snapshot(List.of(first, second)).revision())
                .isEqualTo(CollectionLimits.snapshot(List.of(second, first)).revision());
        assertThat(CollectionLimits.snapshot(List.of(first)).revision())
                .isNotEqualTo(CollectionLimits.snapshot(List.of(new CollectedSnapshot.File("renamed.txt", new byte[] {1}))).revision());
    }

    @Test
    void rejectsPrivateLoopbackMetadataAndReservedDestinations() throws Exception {
        for (String address : List.of("127.0.0.1", "10.0.0.1", "172.16.1.1", "192.168.0.1",
                "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fc00::1",
                "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::")) {
            assertThat(PublicHttpReader.publicAddress(InetAddress.getByName(address))).as(address).isFalse();
        }
        assertThat(PublicHttpReader.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(PublicHttpReader.publicAddress(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        for (String url : List.of("file:///etc/passwd", "http://example.org", "https://user:secret@example.org",
                "https://example.org:8443", "https://127.0.0.1", "https://[::1]", "https://localhost")) {
            assertThatThrownBy(() -> PublicHttpReader.validate(url)).as(url).isInstanceOf(LibraryException.class);
        }
    }

    @Test
    void githubAcceptsOnlyPublicRepositoryAddressShape() {
        var github = new GithubCollector(mock(RemoteReader.class), new GitRunner());
        github.validate("https://github.com/example/synthetic.git");
        for (String url : List.of("git@github.com:example/repo", "file:///repo", "https://github.com.evil/repo/test",
                "https://github.com/example/repo?token=secret", "https://github.com/example/repo/tree/main")) {
            assertThatThrownBy(() -> github.validate(url)).isInstanceOf(LibraryException.class);
        }
    }

    @Test
    void gitReadsCommittedBlobsWithoutCheckoutOrTouchingWorkflow() throws Exception {
        GitRunner runner = new GitRunner();
        fixtureGit(directory, 4096, "init", "--quiet");
        Files.createDirectories(directory.resolve(".github/workflows"));
        Files.writeString(directory.resolve(".github/workflows/test.yml"), "name: synthetic");
        Files.writeString(directory.resolve("Sample.java"), "class Sample { void observe() {} }");
        fixtureGit(directory, 4096, "add", "--", ".");
        fixtureGit(directory, 4096, "-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.test",
                "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Synthetic fixture");
        String revision = new String(runner.run(directory, 1024, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
        var collector = new GithubCollector(mock(RemoteReader.class), runner);
        var snapshot = collector.readSnapshot(directory, revision);
        assertThat(snapshot.revision()).isEqualTo(revision);
        assertThat(snapshot.files()).extracting(CollectedSnapshot.File::path)
                .containsExactly(".github/workflows/test.yml", "Sample.java");
        assertThat(Files.readString(directory.resolve(".github/workflows/test.yml"))).isEqualTo("name: synthetic");
        assertThat(new String(fixtureGit(directory, 4096, "status", "--porcelain"), StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void githubVerifiesApiRevisionBeforePublishing() throws Exception {
        RemoteReader remote = mock(RemoteReader.class);
        GitRunner git = mock(GitRunner.class);
        URI api = URI.create("https://api.github.com/repos/example/synthetic/commits?per_page=1");
        when(remote.read(api)).thenReturn(new RemoteReader.Response(api, "application/json",
                ("[{\"sha\":\"" + "a".repeat(40) + "\"}]").getBytes(StandardCharsets.UTF_8)));
        when(git.run(any(), anyInt(), any(String[].class))).thenReturn(new byte[0]);
        var collector = new GithubCollector(remote, git);
        assertThatThrownBy(() -> collector.collect("https://github.com/example/synthetic"))
                .isInstanceOf(LibraryException.class).hasMessageContaining("변경");
        verify(remote).read(api);
    }

    @Test
    void sameGithubCommitUsesStoredSnapshotWithoutCloning() throws Exception {
        RemoteReader remote = mock(RemoteReader.class);
        GitRunner git = mock(GitRunner.class);
        String revision = "a".repeat(40);
        URI api = URI.create("https://api.github.com/repos/example/synthetic/commits?per_page=1");
        when(remote.read(api)).thenReturn(new RemoteReader.Response(api, "application/json",
                ("[{\"sha\":\"" + revision + "\"}]").getBytes(StandardCharsets.UTF_8)));
        var previous = new CollectedSnapshot(revision, List.of(new CollectedSnapshot.File("synthetic.txt", new byte[] {1})));
        assertThat(new GithubCollector(remote, git).collect("https://github.com/example/synthetic", previous)).isSameAs(previous);
        verifyNoInteractions(git);
    }

    @Test
    void changedGitTreeReusesUnchangedAndRenamedBlobsAndDropsDeletedPaths() throws Exception {
        GitRunner runner = spy(new GitRunner());
        fixtureGit(directory, 4096, "init", "--quiet");
        Files.writeString(directory.resolve("stable.txt"), "stable synthetic");
        Files.writeString(directory.resolve("rename.txt"), "rename synthetic");
        Files.writeString(directory.resolve("edit.txt"), "old synthetic");
        Files.writeString(directory.resolve("delete.txt"), "deleted synthetic");
        String first = commitFixture(runner);
        GithubCollector collector = new GithubCollector(mock(RemoteReader.class), runner);
        var previous = collector.readSnapshot(directory, first);
        Files.move(directory.resolve("rename.txt"), directory.resolve("renamed.txt"));
        Files.delete(directory.resolve("delete.txt"));
        Files.writeString(directory.resolve("edit.txt"), "new synthetic");
        String second = commitFixture(runner);
        clearInvocations(runner);
        var collected = collector.readSnapshot(directory, second, previous);
        assertThat(collected.revision()).isEqualTo(second);
        assertThat(collected.files()).extracting(CollectedSnapshot.File::path).containsExactly("edit.txt", "renamed.txt", "stable.txt");
        assertThat(new String(collected.files().get(0).content(), StandardCharsets.UTF_8)).isEqualTo("new synthetic");
        verify(runner).run(directory, 8 * 1024 * 1024, "ls-tree", "-rz", second);
        verify(runner).run(eq(directory), anyInt(), eq("cat-file"), anyString(), anyString());
        verify(runner).readBlob(eq(directory), eq("edit.txt"), anyString(), anyLong());
        clearInvocations(runner);
        collector.readSnapshot(directory, second, collected);
        verify(runner, never()).run(eq(directory), anyInt(), eq("cat-file"), anyString(), anyString());
        verify(runner, never()).readBlob(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void gitRejectsMismatchedBlobAndLinksBeforePublishing() throws Exception {
        GitRunner runner = mock(GitRunner.class);
        String revision = "a".repeat(40);
        String blob = "b".repeat(40);
        when(runner.run(directory, 8 * 1024 * 1024, "ls-tree", "-rz", revision))
                .thenReturn(("100644 blob " + blob + "\tfile.txt\u0000").getBytes(StandardCharsets.UTF_8));
        when(runner.run(directory, 32, "cat-file", "-s", blob)).thenReturn("1".getBytes(StandardCharsets.UTF_8));
        when(runner.readBlob(directory, "file.txt", blob, 1)).thenReturn(new CollectedSnapshot.File("file.txt", new byte[] {1}));
        GithubCollector collector = new GithubCollector(mock(RemoteReader.class), runner);
        assertThatThrownBy(() -> collector.readSnapshot(directory, revision)).isInstanceOf(LibraryException.class).hasMessageContaining("해시");
        when(runner.run(directory, 8 * 1024 * 1024, "ls-tree", "-rz", revision))
                .thenReturn(("120000 blob " + blob + "\tlink\u0000").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> collector.readSnapshot(directory, revision)).isInstanceOf(LibraryException.class).hasMessageContaining("링크");
    }

    @Test
    void gitBlobReadFailureKeepsReadableSiblings() throws Exception {
        GitRunner runner = spy(new GitRunner());
        fixtureGit(directory, 4096, "init", "--quiet");
        Files.writeString(directory.resolve("readable.txt"), "synthetic healthy document");
        Files.writeString(directory.resolve("unavailable.txt"), "synthetic unavailable document");
        String revision = commitFixture(runner);
        doThrow(new java.io.IOException("synthetic read failure")).when(runner)
                .readBlob(eq(directory), eq("unavailable.txt"), anyString(), anyLong());
        try (var snapshot = new GithubCollector(mock(RemoteReader.class), runner).readSnapshot(directory, revision)) {
            assertThat(snapshot.files()).extracting(CollectedSnapshot.File::path).containsExactly("readable.txt");
            assertThat(snapshot.failures()).containsKey("unavailable.txt");
        }
    }

    private String commitFixture(GitRunner runner) throws Exception {
        fixtureGit(directory, 4096, "add", "--", ".");
        fixtureGit(directory, 4096, "-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.test",
                "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Synthetic incremental fixture");
        return new String(runner.run(directory, 1024, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
    }

    @Test
    void partialCloneLeavesUnchangedBlobMissingOnTheWire() throws Exception {
        GitRunner runner = new GitRunner();
        fixtureGit(directory, 4096, "init", "--quiet");
        fixtureGit(directory, 4096, "config", "uploadpack.allowFilter", "true");
        Files.writeString(directory.resolve("stable.txt"), "stable synthetic partial clone");
        Files.writeString(directory.resolve("edit.txt"), "old synthetic partial clone");
        String first = commitFixture(runner);
        var previous = new GithubCollector(mock(RemoteReader.class), runner).readSnapshot(directory, first);
        String stableBlob = new String(fixtureGit(directory, 1024, "rev-parse", first + ":stable.txt"), StandardCharsets.UTF_8).strip();
        Files.writeString(directory.resolve("edit.txt"), "new synthetic partial clone");
        String second = commitFixture(runner);
        var missing = new java.util.concurrent.atomic.AtomicReference<String>();
        GitRunner transportFixture = new GitRunner() {
            @Override
            public CollectedSnapshot.File readBlob(Path working, String path, String object, long size) throws Exception {
                byte[] content = run(working, (int) size, "cat-file", "blob", object);
                return CollectedSnapshot.File.copy(path, new java.io.ByteArrayInputStream(content), size);
            }
            public byte[] run(Path working, int limit, String... arguments) throws Exception {
                var safeArguments = new java.util.ArrayList<String>(List.of("-c", "protocol.file.allow=always"));
                for (String argument : arguments) {
                    safeArguments.add(argument.equals("https://github.com/example/synthetic.git") ? directory.toUri().toString() : argument);
                }
                byte[] result = fixtureGit(working, limit, safeArguments.toArray(String[]::new));
                if (arguments.length > 1 && arguments[0].equals("cat-file") && arguments[1].equals("blob")) {
                    missing.set(new String(fixtureGit(working, 65536, "rev-list", "--objects", "--all", "--missing=print"), StandardCharsets.UTF_8));
                }
                return result;
            }
        };
        RemoteReader remote = mock(RemoteReader.class);
        URI api = URI.create("https://api.github.com/repos/example/synthetic/commits?per_page=1");
        when(remote.read(api)).thenReturn(new RemoteReader.Response(api, "application/json",
                ("[{\"sha\":\"" + second + "\"}]").getBytes(StandardCharsets.UTF_8)));
        var result = new GithubCollector(remote, transportFixture).collect("https://github.com/example/synthetic", previous);
        assertThat(result.revision()).isEqualTo(second);
        assertThat(result.files()).hasSize(2);
        assertThat(missing.get()).contains("?" + stableBlob);
        assertThat(new String(result.files().get(0).content(), StandardCharsets.UTF_8)).isEqualTo("new synthetic partial clone");
    }

    private byte[] fixtureGit(Path working, int limit, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null",
                "-c", "core.fsmonitor=false", "-c", "init.templateDir="));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile("dougle-test-git-", ".out");
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(Files.size(output)).isLessThanOrEqualTo(limit);
            return Files.readAllBytes(output);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(output);
        }
    }
}
