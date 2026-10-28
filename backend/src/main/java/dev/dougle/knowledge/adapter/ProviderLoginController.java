package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.ProviderLogin;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/logins")
public class ProviderLoginController {
    private final ProviderLogin login;
    public ProviderLoginController(ProviderLogin login) { this.login = login; }
    public record Request(Provider provider, Boolean acknowledged) {}
    @PostMapping
    public ProviderLogin.Status start(@RequestBody Request request) {
        if (request.provider() == null) throw new dev.dougle.library.application.LibraryException(400, "AI 제공자를 선택하세요.");
        return login.start(request.provider(), Boolean.TRUE.equals(request.acknowledged()));
    }
    @GetMapping("/{id}") public ProviderLogin.Status status(@PathVariable String id) { return login.status(id); }
    @DeleteMapping("/{id}") public ProviderLogin.Status cancel(@PathVariable String id) { return login.cancel(id); }
}
