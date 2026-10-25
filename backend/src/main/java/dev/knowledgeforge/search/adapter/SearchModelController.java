package dev.knowledgeforge.search.adapter;

import dev.knowledgeforge.search.application.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search-model")
public class SearchModelController {
    private final LocalSearchModel model;
    private final SearchService search;

    public SearchModelController(LocalSearchModel model, SearchService search) {
        this.model = model;
        this.search = search;
    }

    @GetMapping
    public LocalSearchModel.Status status() { return model.status(); }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startBundledModel() {
        if (model.automatic()) model.prepare(search::initializeIndex);
    }

    @PostMapping("/prepare")
    public LocalSearchModel.Status prepare() {
        model.prepare(search::initializeIndex);
        return model.status();
    }

    @PostMapping("/cancel")
    public LocalSearchModel.Status cancel() {
        model.cancel();
        return model.status();
    }
}
