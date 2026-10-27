package dev.knowledgeforge;

import com.zaxxer.hikari.HikariDataSource;
import dev.knowledgeforge.search.adapter.JdbcEvidenceSourceStore;
import dev.knowledgeforge.search.application.EvidenceSourceStore;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.search.domain.EvidenceWindow;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class EvidenceSourceTransactionTest {
    @TempDir Path directory;

    @Test void singleConnectionReadsAndCallbackKeepOneSnapshotAcrossReplacement() {
        String url = "jdbc:sqlite:" + directory.resolve("transaction.db");
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(url);
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(500);
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("PRAGMA journal_mode=WAL");
            jdbc.execute("CREATE TABLE entry(id TEXT,workspace_id TEXT,source_id TEXT,relative_path TEXT,name TEXT,kind TEXT,area TEXT,content BLOB)");
            jdbc.execute("CREATE TABLE source(id TEXT,revision TEXT)");
            jdbc.execute("CREATE TABLE extracted_document(entry_id TEXT,text TEXT,status TEXT)");
            jdbc.execute("CREATE TABLE extracted_text_part(entry_id TEXT,ordinal INTEGER,text TEXT,PRIMARY KEY(entry_id,ordinal))");
            jdbc.update("INSERT INTO source VALUES('source','revision')");
            jdbc.update("INSERT INTO entry VALUES('entry','workspace','source','fixture.txt','fixture.txt','FILE','ORIGINAL',x'01')");
            jdbc.update("INSERT INTO extracted_document VALUES('entry','preview','READY')");
            jdbc.update("INSERT INTO extracted_text_part VALUES('entry',0,'prefix' || char(10))");
            jdbc.update("INSERT INTO extracted_text_part VALUES('entry',1,'original tail')");
            var proxy = new ProxyFactory(new JdbcEvidenceSourceStore(jdbc));
            proxy.setInterfaces(EvidenceSourceStore.class);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(pool), new AnnotationTransactionAttributeSource()));
            var sources = (EvidenceSourceStore) proxy.getProxy();
            assertThat(sources.material("workspace", "entry")).isPresent();
            assertThat(sources.currentMaterial("workspace", "old-id", "source", "fixture.txt")).isPresent();
            var external = new JdbcTemplate(new DriverManagerDataSource(url));
            var frozen = sources.withMaterial("workspace", "entry", found -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
                var material = found.orElseThrow();
                external.update("UPDATE extracted_text_part SET text='replacement tail' WHERE ordinal=1");
                external.update("UPDATE entry SET content=x'02'");
                assertThat(material.textHash()).isEqualTo(ContentDigest.of("prefix\noriginal tail"));
                assertThat(material.contentHash()).isEqualTo(ContentDigest.of(new byte[] {1}));
                return material.window("original tail", 2, 2, 768, true);
            });
            assertThat(frozen).isEqualTo(new EvidenceWindow("original tail", 2, 2));
            String latest = sources.withMaterial("workspace", "entry", found -> found.orElseThrow().textHash());
            assertThat(latest).isEqualTo(ContentDigest.of("prefix\nreplacement tail"));
            assertThat(sources.material("workspace", "entry").orElseThrow().contentHash()).isEqualTo(ContentDigest.of(new byte[] {2}));
            boolean missing = sources.withMaterial("other", "entry", found -> found.isEmpty());
            assertThat(missing).isTrue();
            assertThatThrownBy(() -> sources.withMaterial("workspace", "entry", found -> {
                throw new IllegalStateException("synthetic callback failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }
}
