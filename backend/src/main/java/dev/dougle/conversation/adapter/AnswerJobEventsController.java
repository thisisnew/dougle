package dev.dougle.conversation.adapter;

import dev.dougle.conversation.application.AnswerJobService;
import dev.dougle.conversation.domain.AnswerJob;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.EvidenceBundleService;
import dev.dougle.search.domain.EvidenceBundle;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/answer-jobs")
public class AnswerJobEventsController {
    private final AnswerJobService jobs;
    private final EvidenceBundleService bundles;
    private final Set<Thread> observers = new HashSet<>();
    private boolean closing;

    public AnswerJobEventsController(AnswerJobService jobs, EvidenceBundleService bundles) {
        this.jobs = jobs; this.bundles = bundles;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public synchronized SseEmitter events(@PathVariable String workspaceId, @PathVariable String id) {
        var job = jobs.get(workspaceId, id);
        var bundle = bundles.get(workspaceId, job.bundleId());
        if (closing) throw new LibraryException(503, "앱이 종료 중입니다.");
        if (observers.size() >= 16) throw new LibraryException(429, "답변 관측 연결이 많습니다. 잠시 후 다시 연결하세요.");
        var emitter = new SseEmitter(60_000L);
        var observer = Thread.ofVirtual().name("forge-answer-events-" + id)
                .unstarted(() -> observe(emitter, workspaceId, id, bundle));
        emitter.onCompletion(observer::interrupt);
        emitter.onTimeout(observer::interrupt);
        emitter.onError(error -> observer.interrupt());
        observers.add(observer);
        try { observer.start(); }
        catch (RuntimeException exception) { observers.remove(observer); throw exception; }
        return emitter;
    }

    private void observe(SseEmitter emitter, String workspaceId, String id, EvidenceBundle bundle) {
        try {
            emitter.send(SseEmitter.event().name("evidence").data(bundle));
            long deadline = System.nanoTime() + 55_000_000_000L;
            long heartbeat = System.nanoTime();
            AnswerJob previous = null;
            String previousText = "";
            while (!Thread.currentThread().isInterrupted()) {
                var current = jobs.get(workspaceId, id);
                if (!current.equals(previous)) {
                    emitter.send(SseEmitter.event().name("job").data(current));
                    previous = current;
                }
                if (!current.active()) {
                    emitter.send(SseEmitter.event().name("done").data(Map.of("reason", "terminal")));
                    break;
                }
                var partial = jobs.partial(workspaceId, id);
                if (partial != null && !partial.text().equals(previousText)) {
                    emitter.send(SseEmitter.event().name("partial").data(partial));
                    previousText = partial.text();
                }
                long now = System.nanoTime();
                if (now >= deadline) {
                    emitter.send(SseEmitter.event().name("done").data(Map.of("reason", "reconnect")));
                    break;
                }
                if (now - heartbeat >= 5_000_000_000L) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeat = now;
                }
                Thread.sleep(500);
            }
            emitter.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (LibraryException exception) {
            try {
                emitter.send(SseEmitter.event().name("unavailable").data(Map.of("message", "작업을 조회할 수 없습니다. 다시 확인하세요.")));
                emitter.complete();
            } catch (IOException | IllegalStateException disconnected) { emitter.completeWithError(disconnected); }
        } catch (IOException | RuntimeException exception) {
            emitter.completeWithError(exception);
        } finally {
            synchronized (this) { observers.remove(Thread.currentThread()); }
        }
    }

    @PreDestroy
    public synchronized void close() {
        closing = true;
        observers.forEach(Thread::interrupt);
    }
}
