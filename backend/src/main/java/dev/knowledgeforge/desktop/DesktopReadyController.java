package dev.knowledgeforge.desktop;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopReadyController {
    private final DesktopSession session;

    public DesktopReadyController(DesktopSession session) {
        this.session = session;
    }

    @GetMapping("/api/desktop/ready")
    public ResponseEntity<Map<String, Boolean>> ready() {
        if (!session.enabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.status(session.ready() ? 200 : 503).body(Map.of("ready", session.ready()));
    }
}
