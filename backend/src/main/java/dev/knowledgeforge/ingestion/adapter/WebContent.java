package dev.knowledgeforge.ingestion.adapter;

public final class WebContent {
    private WebContent() {}

    public static String readableHtml(String html) {
        return readableHtml(org.jsoup.Jsoup.parse(html));
    }

    public static String readableHtml(byte[] html) throws java.io.IOException {
        try (var input = new java.io.ByteArrayInputStream(html)) {
            return readableHtml(org.jsoup.Jsoup.parse(input, null, ""));
        }
    }

    private static String readableHtml(org.jsoup.nodes.Document document) {
        document.charset(java.nio.charset.StandardCharsets.UTF_8);
        document.select("script,style,noscript,nav,footer,form,iframe,[aria-hidden=true],[hidden]").remove();
        var primary = document.select("main,article,[role=main]");
        if (primary.isEmpty()) return document.outerHtml();
        var result = org.jsoup.Jsoup.parse("<html><head></head><body></body></html>");
        result.title(document.title());
        for (var element : primary) {
            if (element.parents().stream().noneMatch(primary::contains)) result.body().appendChild(element.clone());
        }
        return result.outerHtml();
    }
}
