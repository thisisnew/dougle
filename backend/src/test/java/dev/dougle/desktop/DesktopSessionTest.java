package dev.dougle.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DesktopSessionTest {
    private static final String TOKEN = "a1".repeat(32);

    @Test
    void acceptsOnlyExactHeaderAndDoesNotConsumePastFirstLine() {
        ByteArrayInputStream input = input(TOKEN + "\r\nx");
        DesktopSession session = DesktopSession.read(input);
        assertThat(session.authenticates(TOKEN)).isTrue();
        assertThat(session.authenticates("b" + TOKEN.substring(1))).isFalse();
        assertThat(session.authenticates(null)).isFalse();
        assertThat(session.authenticates(TOKEN + "x")).isFalse();
        assertThat(input.read()).isEqualTo('x');
        assertThat(session.toString()).doesNotContain(TOKEN);
    }

    @Test
    void rejectsMissingMalformedUnterminatedAndOverlongBootstrap() {
        for (String value : new String[]{"", "\n", "x".repeat(64) + "\n", TOKEN,
                TOKEN + "a\n", TOKEN.substring(1) + "\n"}) {
            assertThatThrownBy(() -> DesktopSession.read(input(value)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Desktop stdin bootstrap failed")
                    .hasNoCause();
        }
    }

    @Test
    void parentEofClosesAndCannotBecomeReadyAgain() throws Exception {
        DesktopSession session = DesktopSession.read(input(TOKEN + "\n"));
        assertThat(session.markReady()).isTrue();
        CountDownLatch closed = new CountDownLatch(1);
        session.watchParent(input(""), closed::countDown);
        assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(session.ready()).isFalse();
        assertThat(session.markReady()).isFalse();
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }
}
