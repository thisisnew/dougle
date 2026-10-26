package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.ToolExecution;
import java.util.List;

public interface BrowserCaptureTransport {
    int MAX_SECONDS = 30;
    int MAX_REQUESTS = 32;
    int MAX_RESPONSE_BYTES = 512 * 1024;
    int MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    int MAX_TEXT_CHARACTERS = 6000;

    record Capability(String status, String message) {}
    record Observation(String method, String path, Integer status, boolean blocked, String candidatePath) {
        public Observation(String method, String path, Integer status, boolean blocked) { this(method, path, status, blocked, null); }
    }
    record Snapshot(String url, String capturedAt, String title, String text,
                    List<Observation> observations, boolean truncated, String status) {
        public Snapshot { observations = List.copyOf(observations); }
    }

    Capability capability();
    Snapshot capture(BrowserCaptureScope scope, ToolExecution.Context context) throws Exception;
}
