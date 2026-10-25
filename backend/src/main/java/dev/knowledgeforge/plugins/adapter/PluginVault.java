package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Properties;
import java.io.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PluginVault {
    private final Path directory;
    private final SecureRandom random = new SecureRandom();

    public PluginVault(@Value("${forge.data-dir}") String root) {
        directory = Path.of(root).toAbsolutePath().normalize().resolve("plugin-secrets");
    }

    public synchronized String get(String name) { return read().getProperty(name); }

    public synchronized void put(String name, String value) {
        var values = read();
        if (value == null) values.remove(name); else values.setProperty(name, value);
        try {
            var output = new ByteArrayOutputStream();
            values.store(output, null);
            if (output.size() > 65536 - 28) throw failure();
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            var encrypted = new ByteArrayOutputStream();
            encrypted.write(nonce);
            encrypted.write(cipher.doFinal(output.toByteArray()));
            Path target = directory.resolve("credentials.enc");
            safe(target);
            Path temporary = Files.createTempFile(directory, "credentials-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.write(temporary, encrypted.toByteArray());
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (Exception exception) { throw failure(); }
    }

    private Properties read() {
        try {
            prepare();
            Path path = directory.resolve("credentials.enc");
            safe(path);
            var values = new Properties();
            if (!Files.exists(path)) return values;
            if (Files.size(path) > 65536) throw failure();
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 28) throw failure();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
            values.load(new ByteArrayInputStream(cipher.doFinal(bytes, 12, bytes.length - 12)));
            return values;
        } catch (Exception exception) { throw failure(); }
    }

    private void prepare() throws IOException {
        if (Files.isSymbolicLink(directory)) throw failure();
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private SecretKeySpec key() throws IOException {
        Path path = directory.resolve("key");
        safe(path);
        if (!Files.exists(path)) {
            if (Files.exists(directory.resolve("credentials.enc"))) throw failure();
            byte[] key = new byte[32];
            random.nextBytes(key);
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(path, key);
        }
        if (Files.size(path) != 32) throw failure();
        return new SecretKeySpec(Files.readAllBytes(path), "AES");
    }

    private void safe(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw failure();
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private LibraryException failure() { return new LibraryException(500, "플러그인 연결 정보를 안전하게 저장하거나 읽지 못했습니다."); }
}
