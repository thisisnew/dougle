package dev.knowledgeforge.ingestion.adapter;

public final class WebContent {
    private WebContent() {}

    public static final class AccessRequiredException extends RuntimeException {
        private AccessRequiredException(String message) { super(message); }
    }

    public static String readableHtml(String html) {
        return readableHtml(org.jsoup.Jsoup.parse(html));
    }

    public static String readableHtml(byte[] html) throws java.io.IOException {
        try (var input = new java.io.ByteArrayInputStream(html)) {
            return readableHtml(org.jsoup.Jsoup.parse(input, null, ""));
        }
    }

    private static String readableHtml(org.jsoup.nodes.Document document) {
        String heading = document.select("h1").text().strip().toLowerCase(java.util.Locale.ROOT);
        String title = document.title().strip().toLowerCase(java.util.Locale.ROOT);
        boolean shortPage = document.body().text().length() < 2000;
        if (shortPage && !document.select("form input[type=password]").isEmpty()
                && (heading.matches("sign in|log in|login|로그인") || title.matches("sign in|log in|login|로그인"))) {
            throw new AccessRequiredException("로그인이 필요한 페이지입니다. 플러그인을 연결하거나 열람 가능한 링크를 등록하세요.");
        }
        if (shortPage && (heading.matches("verify you are human|checking your browser|사람인지 확인")
                || title.matches("just a moment\\.?\\.?\\.?|attention required!.*"))) {
            throw new AccessRequiredException("자동 접근 확인 화면입니다. 접근 가능한 원문 링크나 파일을 등록하세요.");
        }
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
