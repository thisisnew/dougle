package dev.knowledgeforge.plugins.slack.application;

public interface SlackReconnectScheduler {
    interface Pending { void cancel(); }
    Pending schedule(Runnable action, int seconds);
}
