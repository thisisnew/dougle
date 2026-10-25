package dev.knowledgeforge.plugins.adapter;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final PluginConnections connections;
    private final BrowserPluginConnections browser;
    public PluginController(PluginConnections connections, BrowserPluginConnections browser) { this.connections = connections; this.browser = browser; }
    public record NotionRequest(String token) { @Override public String toString() { return "Notion token [redacted]"; } }
    @GetMapping public java.util.Map<String, Object> status() {
        var saved = connections.status();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("notionConnected", saved.notionConnected() || browser.connected(BrowserProvider.NOTION));
        result.put("atlassianConnected", browser.connected(BrowserProvider.ATLASSIAN));
        result.put("mysqlConnected", saved.mysqlConnected()); result.put("mysql", saved.mysql());
        return result;
    }
    @PostMapping("/notion/test") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void testNotion(@RequestBody NotionRequest request) { connections.notion(request.token(), false); }
    @PostMapping("/notion") public java.util.Map<String, Object> notion(@RequestBody NotionRequest request) { connections.notion(request.token(), true); return status(); }
    @PostMapping("/mysql/test") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void testMysql(@RequestBody MysqlClient.Config request) { connections.mysql(request, false); }
    @PostMapping("/mysql") public java.util.Map<String, Object> mysql(@RequestBody MysqlClient.Config request) { connections.mysql(request, true); return status(); }
    @DeleteMapping("/{provider}") public java.util.Map<String, Object> disconnect(@PathVariable String provider) {
        if (!provider.equals("mysql")) browser.disconnect(BrowserProvider.parse(provider));
        if (!provider.equals("atlassian")) connections.disconnect(provider);
        return status();
    }
    @PostMapping("/browser/{provider}") public BrowserPluginConnections.Progress start(@PathVariable String provider) { return browser.start(BrowserProvider.parse(provider)); }
    @GetMapping("/browser/{provider}/{id}") public BrowserPluginConnections.Progress progress(@PathVariable String provider, @PathVariable String id) { return browser.progress(BrowserProvider.parse(provider), id); }
    @DeleteMapping("/browser/{provider}/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String provider, @PathVariable String id) { browser.cancel(BrowserProvider.parse(provider), id); }
}
