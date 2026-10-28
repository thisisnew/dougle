package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/launch-settings")
public class CliLaunchController {
    private final CliLaunchConfiguration settings;
    public CliLaunchController(CliLaunchConfiguration settings) { this.settings = settings; }
    public record Request(String codexPath, String claudePath, Integer expectedRevision, Boolean acknowledged) {}

    @GetMapping
    public CliLaunchConfiguration.View get() { return settings.view(); }

    @PutMapping
    public CliLaunchConfiguration.View save(@RequestBody Request request) {
        return settings.save(request.codexPath(), request.claudePath(), request.expectedRevision(), request.acknowledged());
    }
}
