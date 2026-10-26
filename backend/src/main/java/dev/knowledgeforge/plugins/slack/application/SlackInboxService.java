package dev.knowledgeforge.plugins.slack.application;

import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.slack.domain.SlackEvent;
import dev.knowledgeforge.security.application.CredentialContentGuard;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SlackInboxService implements SlackReplyAccess {
    public static final int MAX_ITEMS = 200;
    public static final int MAX_SEEN = 2000;
    private static final Duration DEDUPE_WINDOW = Duration.ofHours(24);
    public record Credentials(String appToken, String botToken, boolean acknowledged) {
        @Override public String toString() { return "Slack credentials [redacted]"; }
    }
    public record Status(String state, String teamId, int inboxCount, long ignoredEvents, boolean sessionOnly,
                         boolean tokensSessionOnly, String channelPermissions, boolean gapPossible) {}
    public record Route(String teamId, String channelId, String workspaceId) {}
    public record Sent(String threadId, String bundleId) {}
    public static final class MissingThreadException extends LibraryException {
        private final String expectedThreadId;
        public MissingThreadException(String expectedThreadId) {
            super(409, "연결된 대화가 삭제되었습니다. 새 대화 저장을 명시적으로 확인하세요.");
            this.expectedThreadId = expectedThreadId;
        }
        public String expectedThreadId() { return expectedThreadId; }
    }
    public record Indicator(String state, long count) {}
    public record Item(String id, String workspaceId, SlackEvent event, String receivedAt, String sentQuestion, Sent sent) {}
    private final SlackProvider provider;
    private final LibraryStore library;
    private final ChatThreadService threads;
    private final Clock clock;
    private final SlackInboxStore store;
    private final SlackReconnectScheduler scheduler;
    private SlackReconnectScheduler.Pending reconnect;
    private Credentials credentials;
    private int reconnectAttempts;
    private SlackProvider.Identity identity;
    private SlackProvider.Session session;
    private String state = "NOT_CONFIGURED";
    private long generation;
    private long ignored;
    private final String replyProcessId = UUID.randomUUID().toString();

    @Autowired
    public SlackInboxService(SlackProvider provider, LibraryStore library, ChatThreadService threads, SlackInboxStore store, SlackReconnectScheduler scheduler) {
        this(provider, library, threads, store, scheduler, Clock.systemUTC());
    }

    SlackInboxService(SlackProvider provider, LibraryStore library, ChatThreadService threads, SlackInboxStore store, SlackReconnectScheduler scheduler, Clock clock) {
        this.provider = provider; this.library = library; this.threads = threads; this.store = store; this.scheduler = scheduler; this.clock = clock;
    }

    public synchronized Status status() {
        return new Status(state, identity == null ? null : identity.teamId(), store.itemCount(), ignored,
                false, true, state.equals("PERMISSION_DENIED") ? "DENIED" : "NOT_CHECKED", true);
    }

    public Status connect(Credentials credentials) {
        return connect(credentials, null);
    }

    private Status connect(Credentials credentials, Long expectedGeneration) {
        if (credentials == null || !credentials.acknowledged()) throw new LibraryException(400, "Slack 수신 연결에 명시적으로 동의하세요.");
        if (!token(credentials.appToken(), "xapp-") || !token(credentials.botToken(), "xoxb-")) {
            throw new LibraryException(400, "올바른 Slack 앱 토큰과 봇 토큰이 필요합니다.");
        }
        long current;
        SlackProvider.Session previous;
        synchronized (this) {
            if (expectedGeneration != null && (generation != expectedGeneration || !state.equals("RECONNECTING"))) return status();
            if (expectedGeneration == null && List.of("CONNECTING", "CONNECTED", "RECONNECTING").contains(state)) throw new LibraryException(409, "기존 연결을 먼저 해제하세요.");
            if (expectedGeneration == null) reconnectAttempts = 0;
            if (reconnect != null) { reconnect.cancel(); reconnect = null; }
            this.credentials = credentials;
            previous = session; session = null;
            identity = null; state = "CONNECTING"; current = ++generation;
        }
        if (previous != null) previous.close();
        try {
            var verified = provider.authenticate(credentials.botToken());
            synchronized (this) {
                if (generation != current) return status();
                identity = verified;
            }
            var opened = provider.open(credentials.appToken(), new SlackProvider.Listener() {
                @Override public void ready() {
                    synchronized (SlackInboxService.this) {
                        if (generation == current && state.equals("CONNECTING")) state = "CONNECTED";
                    }
                }
                @Override public boolean event(SlackEvent event) { return receive(current, event); }
                @Override public void closed() {
                    synchronized (SlackInboxService.this) {
                        if (generation == current && !state.equals("INBOX_FULL")) scheduleReconnect(current);
                    }
                }
                @Override public boolean accessLost(String teamId, String userId) {
                    synchronized (SlackInboxService.this) {
                        if (generation != current || identity == null || !identity.teamId().equals(teamId)
                                || userId != null && !identity.botUserId().equals(userId)) return false;
                        generation++;
                        state = "PERMISSION_DENIED";
                        SlackInboxService.this.credentials = null;
                        identity = null;
                        if (reconnect != null) { reconnect.cancel(); reconnect = null; }
                        return true;
                    }
                }
            });
            boolean keep;
            synchronized (this) {
                keep = generation == current && List.of("CONNECTING", "CONNECTED").contains(state);
                if (keep) session = opened;
            }
            if (!keep) opened.close();
            return status();
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (generation == current) {
                    identity = null;
                    if (exception instanceof SlackProvider.AccessFailure failure) {
                        state = failure.kind().name(); this.credentials = null;
                    } else if (expectedGeneration != null) scheduleReconnect(current);
                    else { state = "CONNECTION_FAILED"; this.credentials = null; }
                }
            }
            throw new LibraryException(502, "Slack 인증 또는 연결을 확인하지 못했습니다. 앱 설치·토큰·권한을 확인하세요.");
        }
    }

    private void scheduleReconnect(long current) {
        if (reconnect != null) return;
        if (credentials == null || reconnectAttempts >= 3) {
            state = "RECONNECT_REQUIRED";
            credentials = null;
            return;
        }
        state = "RECONNECTING";
        int delay = 1 << reconnectAttempts++;
        reconnect = scheduler.schedule(() -> {
            Credentials saved;
            synchronized (SlackInboxService.this) {
                if (generation != current || !state.equals("RECONNECTING")) return;
                reconnect = null;
                saved = credentials;
            }
            if (saved == null) return;
            try { connect(saved, current); }
            catch (LibraryException ignoredFailure) { }
        }, delay);
    }

    private static boolean token(String value, String prefix) {
        return value != null && value.startsWith(prefix) && value.length() <= 2048
                && value.length() > prefix.length() && value.matches("[A-Za-z0-9_-]+");
    }

    public Status disconnect() {
        SlackProvider.Session previous;
        synchronized (this) {
            generation++;
            credentials = null;
            if (reconnect != null) { reconnect.cancel(); reconnect = null; }
            state = "DISCONNECTED";
            identity = null;
            previous = session; session = null;
        }
        if (previous != null) previous.close();
        return status();
    }

    public synchronized List<Route> routes() { return store.read(data -> List.copyOf(data.routes().values())); }

    public synchronized Route route(String channelId, String workspaceId) {
        if (!state.equals("CONNECTED") || identity == null) throw new LibraryException(409, "실제 Slack 연결을 먼저 확인하세요.");
        if (channelId == null || !channelId.matches("[CGD][A-Z0-9]{1,63}")) throw new LibraryException(400, "Slack 채널 ID가 필요합니다.");
        requireWorkspace(workspaceId);
        String key = identity.teamId() + "/" + channelId;
        return store.write(data -> {
            if (!data.routes().containsKey(key) && data.routes().size() >= 100) throw new LibraryException(409, "채널 연결은 최대 100개입니다.");
            var route = new Route(identity.teamId(), channelId, workspaceId);
            data.routes().put(key, route);
            return route;
        });
    }

    public synchronized void removeRoute(String teamId, String channelId) {
        store.write(data -> data.routes().remove(teamId + "/" + channelId));
    }

    private synchronized boolean receive(long current, SlackEvent event) {
        if (current != generation || !state.equals("CONNECTED") || identity == null) return false;
        if (!identity.teamId().equals(event.teamId()) || identity.botUserId().equals(event.userId())) { ignored++; return true; }
        return store.write(data -> receive(data, event));
    }

    private boolean receive(SlackInboxStore.State data, SlackEvent event) {
        var route = data.routes().get(event.teamId() + "/" + event.channelId());
        if (route == null) { ignored++; return true; }
        Instant now = clock.instant();
        var seen = data.seen();
        var items = data.items();
        seen.entrySet().removeIf(entry -> !entry.getValue().plus(DEDUPE_WINDOW).isAfter(now));
        String key = event.teamId() + "/event/" + event.eventId();
        String messageKey = event.messageKey();
        var previous = items.values().stream().filter(item -> item.event().messageKey().equals(messageKey)).findFirst().orElse(null);
        if (seen.containsKey(key) || seen.containsKey(messageKey + "/removed")) return true;
        if (previous != null) {
            int revision = new BigDecimal(event.revisionTimestamp()).compareTo(new BigDecimal(previous.event().revisionTimestamp()));
            if (previous.event().deleted() || revision < 0 || revision == 0 && (!event.deleted() || previous.event().deleted())) return true;
            if (!event.deleted() && !previous.event().threadKey().equals(event.threadKey())) return true;
            if (event.deleted()) event = new SlackEvent(event.eventId(), event.teamId(), event.channelId(), previous.event().userId(),
                    event.timestamp(), "", previous.event().threadTimestamp(), event.revisionTimestamp(), true);
        }
        if (previous == null && items.size() >= MAX_ITEMS || seen.size() + 1 > MAX_SEEN) { state = "INBOX_FULL"; return false; }
        var item = previous == null
                ? new Item(UUID.randomUUID().toString(), route.workspaceId(), event, now.toString(), null, null)
                : new Item(previous.id(), previous.workspaceId(), event, previous.receivedAt(), previous.sentQuestion(), previous.sent());
        items.put(item.id(), item);
        seen.put(key, now);
        return true;
    }

    public synchronized List<Item> inbox(String workspaceId) {
        requireWorkspace(workspaceId);
        return store.read(data -> data.items().values().stream().filter(item -> item.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing((Item item) -> new BigDecimal(item.event().timestamp())).thenComparing(Item::id)).toList());
    }

    public synchronized Indicator indicator(String workspaceId) {
        requireWorkspace(workspaceId);
        return new Indicator(state, store.pendingCount(workspaceId));
    }

    public synchronized Sent send(String workspaceId, String id, String question) {
        return send(workspaceId, id, question, false, null);
    }

    public synchronized Sent send(String workspaceId, String id, String question, boolean startNewConversation, String expectedThreadId) {
        requireWorkspace(workspaceId);
        if (startNewConversation != (expectedThreadId != null) || expectedThreadId != null
                && !expectedThreadId.matches("[A-Za-z0-9_-]{1,128}")) throw new LibraryException(400, "새 대화 확인 형식이 올바르지 않습니다.");
        return store.write(data -> send(data, workspaceId, id, question, startNewConversation, expectedThreadId));
    }

    private Sent send(SlackInboxStore.State data, String workspaceId, String id, String question, boolean startNewConversation, String expectedThreadId) {
        var item = requireItem(data, workspaceId, id);
        if (question == null || question.isBlank() || question.length() > 256) throw new LibraryException(400, "대화에 보낼 질문은 1~256자여야 합니다.");
        String normalized = question.strip();
        if (item.sent() != null) {
            if (!Objects.equals(item.sentQuestion(), normalized)) throw new LibraryException(409, "이미 다른 질문으로 대화에 보냈습니다.");
            return item.sent();
        }
        if (item.event().deleted()) throw new LibraryException(409, "Slack에서 삭제된 메시지는 새 대화에 보낼 수 없습니다.");
        CredentialContentGuard.requireSafe(item.event().text());
        CredentialContentGuard.requireSafe(normalized);
        String key = workspaceId + "/" + item.event().threadKey();
        var link = data.threads().get(key);
        if (link == null && data.threads().size() >= 1000) throw new LibraryException(409, "Slack 대화 연결 한도에 도달했습니다.");
        String threadId = link == null ? null : link.threadId();
        if (startNewConversation && !Objects.equals(threadId, expectedThreadId)) {
            throw new LibraryException(409, "대화 연결이 변경되었습니다. 다시 확인하세요.");
        }
        String parentId = null;
        if (threadId != null) {
            String linkedThreadId = threadId;
            boolean missing = threads.list(workspaceId).stream().noneMatch(thread -> thread.id().equals(linkedThreadId));
            if (missing) {
                if (!startNewConversation) throw new MissingThreadException(threadId);
                threadId = null;
            } else if (startNewConversation) {
                throw new LibraryException(409, "기존 대화가 있어 새 대화로 바꿀 수 없습니다.");
            } else {
                parentId = threads.get(workspaceId, threadId).bundles().getLast().id();
            }
        }
        var bundle = threads.create(workspaceId, new ChatThreadService.Create("slack-" + id, threadId, parentId, normalized, List.of()));
        var sent = new Sent(bundle.payload().chatContext() == null ? bundle.id() : bundle.payload().chatContext().threadId(), bundle.id());
        data.items().put(id, new Item(item.id(), workspaceId, item.event(), item.receivedAt(), normalized, sent));
        data.threads().put(key, new SlackInboxStore.ThreadLink(workspaceId, sent.threadId()));
        return sent;
    }

    public synchronized void remove(String workspaceId, String id) {
        requireWorkspace(workspaceId);
        store.write(data -> {
            var item = requireItem(data, workspaceId, id);
            Instant now = clock.instant();
            data.seen().entrySet().removeIf(entry -> !entry.getValue().plus(DEDUPE_WINDOW).isAfter(now));
            String key = item.event().messageKey() + "/removed";
            if (!data.seen().containsKey(key) && data.seen().size() >= MAX_SEEN) throw new LibraryException(409, "중복 방지 기록 한도에 도달했습니다. 나중에 다시 시도하세요.");
            data.seen().put(key, now);
            return data.items().remove(id);
        });
    }

    private Item requireItem(SlackInboxStore.State data, String workspaceId, String id) {
        var item = data.items().get(id);
        if (item == null || !item.workspaceId().equals(workspaceId)) throw new LibraryException(404, "Slack 수신 항목을 찾을 수 없습니다.");
        return item;
    }

    private void requireWorkspace(String workspaceId) {
        if (workspaceId == null || library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
    }

    @Override public synchronized SlackReplyAccess.Scope snapshot(String workspaceId, String inboxItemId) {
        requireWorkspace(workspaceId);
        if (!state.equals("CONNECTED") || identity == null || credentials == null) throw new LibraryException(409, "Slack 연결을 다시 확인하세요.");
        return store.read(data -> {
            var item = requireItem(data, workspaceId, inboxItemId);
            var event = item.event();
            var route = data.routes().get(event.teamId() + "/" + event.channelId());
            if (event.deleted() || !event.teamId().equals(identity.teamId()) || route == null || !route.workspaceId().equals(workspaceId)) {
                throw new LibraryException(409, "Slack 메시지 또는 수신 대상이 변경되었습니다.");
            }
            String source = dev.knowledgeforge.search.domain.ContentDigest.of(event.eventId() + "\n" + event.messageKey()
                    + "\n" + event.revisionTimestamp() + "\n" + event.threadTimestamp() + "\n" + event.text());
            return new SlackReplyAccess.Scope(workspaceId, inboxItemId, source, event.teamId(), event.channelId(),
                    event.threadTimestamp(), identity.botUserId(), replyProcessId + ":" + generation);
        });
    }

    @Override public synchronized SlackReplyAccess.Lease authorize(SlackReplyAccess.Scope expected) {
        if (!snapshot(expected.workspaceId(), expected.inboxItemId()).equals(expected)) throw new LibraryException(409, "Slack 전송 범위가 변경되었습니다. 새 미리보기가 필요합니다.");
        return new SlackReplyAccess.Lease(expected, credentials.botToken(), () -> {
            synchronized (SlackInboxService.this) {
                if (!snapshot(expected.workspaceId(), expected.inboxItemId()).equals(expected)) throw new LibraryException(409, "Slack 전송 범위가 변경되었습니다.");
            }
        });
    }

    @PreDestroy public void close() { disconnect(); }
}
