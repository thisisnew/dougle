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
    private final CliLaunchStore.Configuration startup;
    private final String codex;
    private final String claude;
    private final String codexStreamHome;

    @org.springframework.beans.factory.annotation.Autowired
    public CliLaunchConfiguration(CliLaunchStore store, @Value("${forge.ai.codex-command:codex}") String codex,
                                  @Value("${forge.ai.claude-command:claude}") String claude,
                                  @Value("${forge.ai.codex-stream-home:}") String codexStreamHome) {
        this.store = store;
        startup = store.current();
        this.codex = startup.codexPath().isEmpty() ? codex : startup.codexPath();
        this.claude = startup.claudePath().isEmpty() ? claude : startup.claudePath();
        this.codexStreamHome = codexStreamHome;
    }

    public CliLaunchConfiguration(CliLaunchStore store, String codex, String claude) {
        this(store, codex, claude, "");
    }

    public String command(CuratorModel.Provider provider) { return provider == CuratorModel.Provider.CODEX ? codex : claude; }

    public View view() {
        var saved = store.current();
        return new View(saved, codex, claude, !saved.codexPath().equals(startup.codexPath()) || !saved.claudePath().equals(startup.claudePath()));
    }

    @org.springframework.transaction.annotation.Transactional
    public View save(String codexPath, String claudePath, Integer expectedRevision, Boolean acknowledged) {
        if (!Boolean.TRUE.equals(acknowledged) || expectedRevision == null || expectedRevision < 0
                || codexPath == null || claudePath == null) {
            throw new LibraryException(400, "실행 파일을 신뢰하는지 확인하고 설정 revision을 지정하세요.");
        }
        if (!store.replace(codexPath.strip(), claudePath.strip(), expectedRevision)) {
            throw new LibraryException(409, "다른 설정이 저장되었습니다. 설정을 다시 열어 주세요.");
        }
        return view();
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
