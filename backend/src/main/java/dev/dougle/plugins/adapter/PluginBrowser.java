package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class PluginBrowser {
    public void open(BrowserProvider provider, URI uri) {
        provider.trusted(uri.toString());
        openTrusted(uri);
    }
    private void openTrusted(URI uri) {
        try {
            var process = new ProcessBuilder("/usr/bin/open", uri.toString()).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException(); }
            if (process.exitValue() != 0) throw new IllegalStateException();
        } catch (Exception exception) { throw new LibraryException(502, "브라우저를 열지 못했습니다. macOS 기본 웹 브라우저 설정과 회사의 앱 실행 정책을 확인한 뒤 다시 연결해 주세요."); }
    }
}
