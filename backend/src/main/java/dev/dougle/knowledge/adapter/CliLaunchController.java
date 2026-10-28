package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CliDiscovery;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/launch-settings")
public class CliLaunchController {
    private final CliLaunchConfiguration settings;
    private final CliDiscovery discovery;
    private final dev.dougle.knowledge.application.ProviderConnection connection;
    public CliLaunchController(CliLaunchConfiguration settings, CliDiscovery discovery, dev.dougle.knowledge.application.ProviderConnection connection) {
        this.settings = settings;
        this.discovery = discovery;
        this.connection = connection;
    }
    public record Request(String codexPath, String claudePath, Integer expectedRevision, Boolean acknowledged) {}
    public record Selection(String path, Integer expectedRevision) {}

    @PostMapping("/connect/{provider}")
    public dev.dougle.knowledge.application.ProviderConnection.Status connect(@PathVariable Provider provider,
            @RequestBody Selection selection) {
        try (var lease = dev.dougle.knowledge.application.CliExecutionGate.change()) {
            if (selection.expectedRevision() == null || selection.path() == null) {
                throw new dev.dougle.library.application.LibraryException(400, "연결할 도구를 선택하세요.");
            }
            if (settings.view().saved().revision() != selection.expectedRevision()) {
                throw new dev.dougle.library.application.LibraryException(409, "다른 연결 설정이 저장되었습니다. 다시 연결하세요.");
            }
            var candidate = discovery.find(provider).stream().filter(value -> value.path().equals(selection.path())).findFirst()
                    .orElseThrow(() -> new dev.dougle.library.application.LibraryException(409, "실행 환경이 변경됐습니다. 다시 연결하세요."));
            var checked = connection.checkCommand(provider, candidate.path());
            if (java.util.Set.of("AUTHENTICATED", "LOGIN_REQUIRED").contains(checked.state())) {
                settings.activate(provider, candidate.path(), selection.expectedRevision());
            }
            return checked;
        }
    }

    @GetMapping
    public CliLaunchConfiguration.View get() { return settings.view(); }

    @GetMapping("/discovered/{provider}")
    public java.util.List<CliDiscovery.Candidate> discovered(@PathVariable Provider provider) {
        return discovery.find(provider);
    }

    @PutMapping
    public CliLaunchConfiguration.View save(@RequestBody Request request) {
        return settings.save(request.codexPath(), request.claudePath(), request.expectedRevision(), request.acknowledged());
    }
}
