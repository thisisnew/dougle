package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.util.Locale;

public final class WebSourcePolicy {
    private WebSourcePolicy() {}

    public static URI validate(String location) {
        URI uri = PublicHttpReader.validate(location);
        String host = uri.getHost().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        if (host.equals("github.com") || host.endsWith(".github.com")
                || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com")) {
            throw new LibraryException(400, "GitHub 연결은 지원하지 않습니다. 내려받은 소스코드의 로컬 폴더를 선택하세요.");
        }
        return uri;
    }
}
