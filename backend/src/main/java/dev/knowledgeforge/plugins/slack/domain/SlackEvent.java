package dev.knowledgeforge.plugins.slack.domain;

public record SlackEvent(String eventId, String teamId, String channelId, String userId, String timestamp, String text,
                         String threadTimestamp, String revisionTimestamp, boolean deleted) {
    public SlackEvent(String eventId, String teamId, String channelId, String userId, String timestamp, String text) {
        this(eventId, teamId, channelId, userId, timestamp, text, timestamp, timestamp, false);
    }

    public SlackEvent {
        if (eventId == null || !eventId.matches("[A-Za-z0-9_-]{1,128}")
                || teamId == null || !teamId.matches("T[A-Z0-9]{1,63}")
                || channelId == null || !channelId.matches("[CGD][A-Z0-9]{1,63}")
                || (userId == null ? !deleted : !userId.matches("[UW][A-Z0-9]{1,63}"))
                || timestamp == null || !timestamp.matches("[0-9]{10,16}\\.[0-9]{1,6}")
                || !validTimestamp(threadTimestamp) || !validTimestamp(revisionTimestamp)
                || new java.math.BigDecimal(threadTimestamp).compareTo(new java.math.BigDecimal(timestamp)) > 0
                || new java.math.BigDecimal(revisionTimestamp).compareTo(new java.math.BigDecimal(timestamp)) < 0
                || text == null || (!deleted && text.isBlank()) || text.length() > 12000) {
            throw new IllegalArgumentException("Invalid Slack event");
        }
    }

    private static boolean validTimestamp(String value) {
        return value != null && value.matches("[0-9]{10,16}\\.[0-9]{1,6}");
    }

    public String messageKey() { return teamId + "/message/" + channelId + "/" + canonical(timestamp); }
    public String threadKey() { return teamId + "/thread/" + channelId + "/" + canonical(threadTimestamp); }
    public static String canonical(String timestamp) { return new java.math.BigDecimal(timestamp).stripTrailingZeros().toPlainString(); }

    @Override public String toString() { return "Slack event [content redacted]"; }
}
