package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.adapter.CollectionLimits;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class NotionClient {
    private final JsonMapper json = new JsonMapper();
    private final CloseableHttpClient client;
    public NotionClient() { this(HttpClients.custom().disableRedirectHandling().disableCookieManagement()
            .disableAutomaticRetries().setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(5)).setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .setResponseTimeout(Timeout.ofSeconds(15)).build()).build()); }
    NotionClient(CloseableHttpClient client) { this.client = client; }
    private final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

    public void test(String token) { get("users/me", token); }

    public JsonNode get(String path, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_\\-]{20,1024}"))
            throw new LibraryException(400, "Notion 연결 토큰을 확인하세요.");
        var request = new HttpGet("https://api.notion.com/v1/" + path);
        request.setHeader("Authorization", "Bearer " + token);
        request.setHeader("Notion-Version", "2025-09-03");
        var deadline = deadlines.schedule(request::cancel, 25, java.util.concurrent.TimeUnit.SECONDS);
        try {
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status == 401) throw new LibraryException(409, "Notion 연결이 필요합니다. 토큰을 다시 연결하세요.");
                if (status == 403 || status == 404) throw new LibraryException(403, "Notion 페이지 접근 권한이 없습니다. 페이지의 연결 메뉴에서 접근을 허용하세요.");
                if (status == 429) throw new LibraryException(429, "Notion 요청 한도에 도달했습니다. 잠시 후 동기화하세요.");
                if (status != 200 || response.getEntity() == null) throw new LibraryException(502, "Notion 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.");
                try (var input = response.getEntity().getContent()) { return json.readTree(CollectionLimits.read(input)); }
            });
        } catch (LibraryException exception) { throw exception; }
        catch (Exception exception) { throw new LibraryException(502, "Notion에 연결할 수 없습니다. 네트워크를 확인하세요."); }
        finally { deadline.cancel(false); }
    }

    public CollectedSnapshot collect(String location, String token) {
        if (token == null) throw new LibraryException(409, "Notion 연결이 필요합니다. 플러그인에서 Notion을 연결해 주세요.");
        var matcher = Pattern.compile("([a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})(?:/)?$")
                .matcher(URI.create(location).getPath());
        if (!matcher.find()) throw new LibraryException(400, "Notion 페이지 링크를 복사해서 등록하세요.");
        String pageId = matcher.group(1);
        JsonNode page = get("pages/" + pageId, token);
        if (!page.path("properties").isObject()) throw new LibraryException(502, "Notion 페이지 정보를 읽지 못했습니다.");
        var text = new StringBuilder();
        for (JsonNode property : page.path("properties")) if (property.path("type").asText().equals("title")) appendRich(text, property.path("title"));
        text.append('\n');
        blocks(pageId, token, text, 0, new int[]{0}, System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos());
        return CollectionLimits.snapshot(List.of(new CollectedSnapshot.File("notion-page.txt", text.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private void blocks(String id, String token, StringBuilder text, int depth, int[] count, long deadline) {
        if (depth > 12) throw new LibraryException(413, "Notion 페이지가 너무 깊습니다. 하위 페이지로 나누어 등록하세요.");
        String cursor = "";
        do {
            if (++count[0] > 100 || System.nanoTime() > deadline) throw new LibraryException(413, "Notion 페이지가 너무 큽니다. 하위 페이지로 나누어 등록하세요.");
            JsonNode page = get("blocks/" + id + "/children?page_size=100" + cursor, token);
            if (!page.path("results").isArray()) throw new LibraryException(502, "Notion 본문을 읽지 못했습니다.");
            for (JsonNode block : page.path("results")) {
                String type = block.path("type").asText();
                JsonNode content = block.path(type);
                appendRich(text, content.path("rich_text"));
                for (JsonNode cell : content.path("cells")) { appendRich(text, cell); text.append(" | "); }
                if (type.equals("child_page")) text.append(content.path("title").asText());
                if (type.equals("bookmark") || type.equals("link_preview")) text.append(content.path("url").asText());
                text.append('\n');
                if (text.length() > 2_000_000) throw new LibraryException(413, "Notion 페이지가 너무 큽니다. 하위 페이지로 나누어 등록하세요.");
                if (block.path("has_children").asBoolean()) blocks(block.path("id").asText(), token, text, depth + 1, count, deadline);
            }
            if (!page.path("has_more").asBoolean()) break;
            String next = page.path("next_cursor").asText();
            if (next.isBlank()) throw new LibraryException(502, "Notion 페이지 목록을 끝까지 읽지 못했습니다.");
            cursor = "&start_cursor=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
        } while (true);
    }

    private void appendRich(StringBuilder text, JsonNode rich) {
        for (JsonNode part : rich) text.append(part.path("plain_text").asText(part.path("text").path("content").asText("")));
    }

    @PreDestroy public void close() throws Exception { deadlines.shutdownNow(); client.close(); }
}
