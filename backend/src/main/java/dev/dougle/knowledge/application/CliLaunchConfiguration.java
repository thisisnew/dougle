package dev.dougle.knowledge.application;

import dev.dougle.library.application.LibraryException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CliLaunchConfiguration {
    public record View(CliLaunchStore.Configuration saved, String activeCodexCommand, String activeClaudeCommand, boolean restartRequired) {}
    private final CliLaunchStore store;
    private record Active(String codex, String claude, CliLaunchStore.Configuration configuration) {}
    private volatile Active active;
    private final String codexStreamHome;

    @org.springframework.beans.factory.annotation.Autowired
    public CliLaunchConfiguration(CliLaunchStore store, @Value("${forge.ai.codex-command:codex}") String codex,
                                  @Value("${forge.ai.claude-command:claude}") String claude,
                                  @Value("${forge.ai.codex-stream-home:}") String codexStreamHome, CliDiscovery discovery) {
        this(store, discovery.defaultCommand(CuratorModel.Provider.CODEX, codex),
                discovery.defaultCommand(CuratorModel.Provider.CLAUDE, claude), codexStreamHome);
    }

    public CliLaunchConfiguration(CliLaunchStore store, String codex, String claude, String codexStreamHome) {
        this.store = store;
        var startup = store.current();
        this.active = new Active(startup.codexPath().isEmpty() ? codex : startup.codexPath(),
                startup.claudePath().isEmpty() ? claude : startup.claudePath(), startup);
        this.codexStreamHome = codexStreamHome;
    }

    public CliLaunchConfiguration(CliLaunchStore store, String codex, String claude) {
        this(store, codex, claude, "");
    }

    public String command(CuratorModel.Provider provider) {
        var snapshot = active;
        return provider == CuratorModel.Provider.CODEX ? snapshot.codex() : snapshot.claude();
    }

    public View view() {
        var saved = store.current();
        var snapshot = active;
        return new View(saved, snapshot.codex(), snapshot.claude(), !saved.codexPath().equals(snapshot.configuration().codexPath())
                || !saved.claudePath().equals(snapshot.configuration().claudePath()));
    }

    public View save(String codexPath, String claudePath, Integer expectedRevision, Boolean acknowledged) {
        if (!Boolean.TRUE.equals(acknowledged) || expectedRevision == null || expectedRevision < 0
                || codexPath == null || claudePath == null) {
            throw new LibraryException(400, "실행 파일을 신뢰하는지 확인하고 설정 revision을 지정하세요.");
        }
        try (var lease = CliExecutionGate.change()) {
            if (!store.replace(codexPath.strip(), claudePath.strip(), expectedRevision)) {
                throw new LibraryException(409, "다른 설정이 저장되었습니다. 설정을 다시 열어 주세요.");
            }
            return view();
        }
    }

    public void activate(CuratorModel.Provider provider, String path, int expectedRevision) {
        try (var lease = CliExecutionGate.change()) {
            var saved = store.current();
            if (saved.revision() != expectedRevision) throw new LibraryException(409, "다른 연결 설정이 저장되었습니다. 다시 연결하세요.");
            String nextCodex = provider == CuratorModel.Provider.CODEX ? path : saved.codexPath();
            String nextClaude = provider == CuratorModel.Provider.CLAUDE ? path : saved.claudePath();
            if (!store.replace(nextCodex, nextClaude, expectedRevision)) throw new LibraryException(409, "다른 연결 설정이 저장되었습니다. 다시 연결하세요.");
            var previous = active;
            active = new Active(provider == CuratorModel.Provider.CODEX ? path : previous.codex(),
                    provider == CuratorModel.Provider.CLAUDE ? path : previous.claude(),
                    new CliLaunchStore.Configuration(provider == CuratorModel.Provider.CODEX ? path : previous.configuration().codexPath(),
                            provider == CuratorModel.Provider.CLAUDE ? path : previous.configuration().claudePath(), expectedRevision + 1));
        }
    }

    public String binding(CuratorModel.Provider provider) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((provider.name() + "\n" + command(provider)
                            + (provider == CuratorModel.Provider.CODEX && !codexStreamHome.isBlank()
                            ? "\nprivate-stream-profile-v1\n" + codexStreamHome : "")).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
