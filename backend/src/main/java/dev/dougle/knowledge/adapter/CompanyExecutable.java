package dev.dougle.knowledge.adapter;

import dev.dougle.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class CompanyExecutable {
    static String resolve(String value) {
        var directories = new ArrayList<Path>();
        String home = System.getProperty("user.home");
        if (home != null) {
            for (String suffix : List.of(".local/bin", "bin", ".npm-global/bin", ".volta/bin", ".asdf/shims")) {
                directories.add(Path.of(home, suffix));
            }
        }
        directories.add(Path.of("/opt/homebrew/bin"));
        directories.add(Path.of("/usr/local/bin"));
        String environment = System.getenv("PATH");
        if (environment != null) {
            for (String item : environment.split(java.io.File.pathSeparator)) {
                if (!item.isBlank() && Path.of(item).isAbsolute()) directories.add(Path.of(item));
            }
        }
        return resolve(value, directories);
    }

    static String resolve(String value, List<Path> directories) {
        if (value.isEmpty()) return value;
        if (ShellAliasLaunch.known(value)) return value;
        if (value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        try {
            Path input = Path.of(value);
            if (input.isAbsolute()) {
                if (Files.isRegularFile(input) && Files.isExecutable(input)) return input.toString();
                throw invalid();
            }
            if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw invalid();
            for (Path directory : directories) {
                if (!directory.isAbsolute()) continue;
                Path executable = directory.resolve(value);
                if (Files.isRegularFile(executable) && Files.isExecutable(executable)) return executable.toString();
            }
            throw new LibraryException(400, "실행 명령을 찾지 못했습니다. 터미널 별칭이면 회사용 실행 파일의 전체 경로를 지정하세요.");
        } catch (java.nio.file.InvalidPathException exception) { throw invalid(); }
    }

    private static LibraryException invalid() {
        return new LibraryException(400, "실행 가능한 파일의 전체 경로나 명령 이름만 입력하세요. 옵션·셸 명령은 넣지 마세요.");
    }
}
