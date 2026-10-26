package dev.knowledgeforge.plugins.slack.application;

public interface SlackReplyTransport {
    record Result(String status, String messageTimestamp, String failureCode) {}
    Result post(SlackReplyAccess.Lease lease, String body);
}
