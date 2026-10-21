package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.Knowledge.Document;
import dev.knowledgeforge.library.application.LibraryException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GenerationComparisonService {
    private final GenerationJobStore jobs;
    private final KnowledgeService knowledge;

    public GenerationComparisonService(GenerationJobStore jobs, KnowledgeService knowledge) {
        this.jobs = jobs;
        this.knowledge = knowledge;
    }

    public record Comparison(Document base, Document current, Document generated) {}

    public Comparison compare(String workspaceId, String jobId) {
        var job = jobs.find(workspaceId, jobId)
                .orElseThrow(() -> new LibraryException(404, "AI 작업을 찾을 수 없습니다."));
        if (!"SUCCEEDED".equals(job.status()) || job.resultCardId() == null) {
            throw new LibraryException(409, "완료된 AI 작업만 비교할 수 있습니다.");
        }
        Document base = knowledge.get(workspaceId, job.sourceCardId(), job.expectedVersion());
        Document current = knowledge.get(workspaceId, job.sourceCardId(), null);
        Document generated = knowledge.get(workspaceId, job.resultCardId(), 1);
        return new Comparison(base, current, generated);
    }
}
