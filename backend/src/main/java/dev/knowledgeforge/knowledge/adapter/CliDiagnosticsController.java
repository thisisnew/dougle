package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CliDiagnostics;
import dev.knowledgeforge.knowledge.application.CuratorModel;
import dev.knowledgeforge.library.application.LibraryException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/providers")
public class CliDiagnosticsController {
    private final CliDiagnostics diagnostics;

    public CliDiagnosticsController(CliDiagnostics diagnostics) { this.diagnostics = diagnostics; }

    @PostMapping("/{provider}/probe")
    public CliDiagnostics.Status probe(@PathVariable String provider) {
        CuratorModel.Provider selected;
        try { selected = CuratorModel.Provider.valueOf(provider); }
        catch (IllegalArgumentException exception) { throw new LibraryException(400, "CODEX 또는 CLAUDE를 선택하세요."); }
        return diagnostics.probe(selected);
    }
}
