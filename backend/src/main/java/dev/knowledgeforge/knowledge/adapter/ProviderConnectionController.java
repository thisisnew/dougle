package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.ProviderConnection;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/providers")
public class ProviderConnectionController {
    private final ProviderConnection connection;
    public ProviderConnectionController(ProviderConnection connection) { this.connection = connection; }
    @PostMapping("/{provider}/connection")
    public ProviderConnection.Status check(@PathVariable Provider provider) { return connection.check(provider); }
}
