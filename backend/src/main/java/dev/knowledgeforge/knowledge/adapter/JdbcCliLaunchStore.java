package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CliLaunchStore;
import dev.knowledgeforge.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCliLaunchStore implements CliLaunchStore {
    private final JdbcTemplate jdbc;
    public JdbcCliLaunchStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Configuration current() {
        return jdbc.queryForObject("SELECT codex_path,claude_path,revision FROM cli_launch_settings WHERE id=1",
                (result, row) -> new Configuration(result.getString("codex_path"), result.getString("claude_path"), result.getInt("revision")));
    }

    public boolean replace(String codexPath, String claudePath, int expectedRevision) {
        validate(codexPath);
        validate(claudePath);
        return jdbc.update("UPDATE cli_launch_settings SET codex_path=?,claude_path=?,revision=revision+1 WHERE id=1 AND revision=?",
                codexPath, claudePath, expectedRevision) == 1;
    }

    private void validate(String value) {
        if (value.isEmpty()) return;
        try {
            Path path = Path.of(value);
            if (value.length() > 4096 || value.contains(java.io.File.pathSeparator) || value.codePoints().anyMatch(Character::isISOControl)
                    || !path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new LibraryException(400, "CLI 경로는 실행 가능한 파일의 절대 경로여야 합니다. 인자·셸 명령은 넣지 마세요.");
        }
    }
}
