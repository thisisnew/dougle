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
    public CliLaunchController(CliLaunchConfiguration settings, CliDiscovery discovery) {
        this.settings = settings;
        this.discovery = discovery;
    }
    public record Request(String codexPath, String claudePath, Integer expectedRevision, Boolean acknowledged) {}

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
