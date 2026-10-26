package dev.knowledgeforge.plugins.slack.application;

import java.util.LinkedHashMap;
import java.util.function.Function;

public class MemorySlackInboxStore implements SlackInboxStore {
    private State state = new State();
    @Override public synchronized <T> T read(Function<State, T> operation) { return operation.apply(copy()); }
    @Override public synchronized <T> T write(Function<State, T> operation) {
        var next = copy();
        T result = operation.apply(next);
        state = next;
        return result;
    }
    private State copy() {
        return new State(new LinkedHashMap<>(state.routes()), new LinkedHashMap<>(state.items()),
                new LinkedHashMap<>(state.seen()), new LinkedHashMap<>(state.threads()));
    }
}
