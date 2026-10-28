package dev.dougle.sources.adapter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "forge.acceptance.dns", matches = "true")
class DnsRebindingAcceptanceTest {
    private final List<Map<String, Object>> results = new ArrayList<>();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/dns-acceptance-results.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        return IntStream.range(0, 5).mapToObj(action -> DynamicTest.dynamicTest("T" + (491 + action), () -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", "T%04d".formatted(491 + action));
            result.put("startedAt", Instant.now().toString());
            result.put("scope", "REAL_HTTP_CLIENT_CONNECTION_RESOLUTION_WITH_CONTROLLED_DNS_ANSWERS");
            result.put("fullAcceptancePassed", false);
            result.put("remaining", List.of("No live malicious DNS server, TLS server or browser UI",
                    "SourceService persistence, retries and concurrent clicks remain separate"));
            var calls = new AtomicInteger();
            InetAddress publicIp = InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34});
            InetAddress privateIp = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            DnsResolver resolver = new DnsResolver() {
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    assertThat(host).isEqualTo("synthetic-rebinding.example");
                    if (calls.incrementAndGet() == 1) return new InetAddress[]{publicIp};
                    return action == 4 ? new InetAddress[]{publicIp, privateIp} : new InetAddress[]{privateIp};
                }
                public String resolveCanonicalHostname(String host) { return host; }
            };
            try {
                assertThat(PublicHttpReader.publicAddress(resolver.resolve("synthetic-rebinding.example")[0])).isTrue();
                int attempts = action == 2 ? 3 : 1;
                for (int attempt = 0; attempt < attempts; attempt++) {
                    try (var reader = new PublicHttpReader(resolver)) {
                        assertThatThrownBy(() -> reader.read(PublicHttpReader.validate("https://synthetic-rebinding.example/document")))
                                .isInstanceOf(UnknownHostException.class).hasMessageContaining("Non-public destination rejected");
                    }
                }
                assertThat(calls.get()).isEqualTo(1 + attempts);
                result.put("dnsCalls", calls.get());
                result.put("status", "PARTIAL");
                result.put("checkStatus", "PASS");
            } catch (Throwable failure) {
                result.put("status", "FAIL");
                result.put("checkStatus", "FAIL");
                result.put("failure", failure.toString());
                throw failure;
            } finally { results.add(result); }
        }));
    }
}
