package dev.dougle.search.adapter;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import dev.dougle.search.application.TextEmbedding;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalSearchModel implements TextEmbedding, AutoCloseable {
    public record Status(String state, long downloaded, long total, String error) {}
    private record Asset(String remote, String local, long size, String hash) {}
    private static final String BASE = "https://huggingface.co/intfloat/multilingual-e5-small/resolve/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/";
    private static final Asset MODEL = new Asset("model_qint8_avx512_vnni.onnx", "model.onnx", 118346824,
            "dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88");
    private static final Asset TOKENIZER = new Asset("tokenizer.json", "tokenizer.json", 17082730,
            "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39");
    private static final String RERANK_BASE = "https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/1427fd652930e4ba29e8149678df786c240d8825/";
    private static final Asset RERANKER = System.getProperty("os.arch").matches("aarch64|arm64")
            ? new Asset(RERANK_BASE + "onnx/model_qint8_arm64.onnx", "reranker.onnx", 118620017,
                    "1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4")
            : new Asset(RERANK_BASE + "onnx/model_quint8_avx2.onnx", "reranker.onnx", 118620016,
                    "6c2513767fb63d008a4377bef7a7a3555433d9436342bb53e35a3a72ffc52d4b");
    private static final Asset RERANK_TOKENIZER = new Asset(RERANK_BASE + "tokenizer.json", "reranker-tokenizer.json", 17082660,
            "62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626");
    private static final java.util.List<Asset> ASSETS = java.util.List.of(MODEL, TOKENIZER, RERANKER, RERANK_TOKENIZER);
    private static final Set<String> HOSTS = Set.of("huggingface.co", "cdn-lfs.huggingface.co", "cdn-lfs-us-1.hf.co", "cas-bridge.xethub.hf.co", "us.aws.cdn.hf.co");
    private final Path directory;
    private final boolean automatic;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("dougle-search-model").factory());
    private volatile Status status = new Status("NOT_READY", 0, total(), null);
    private volatile boolean cancelled;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private OrtSession reranker;
    private HuggingFaceTokenizer rerankTokenizer;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalSearchModel(@Value("${forge.data-dir}") String dataDirectory,
                            @Value("${forge.search-model-directory:}") String bundledDirectory,
                            @Value("${forge.desktop:false}") boolean desktop) {
        this(dataDirectory, bundledDirectory.isBlank()
                ? new org.springframework.boot.system.ApplicationHome(LocalSearchModel.class).getDir().toPath().resolve("search-model-e5-v1")
                : Path.of(bundledDirectory), desktop || !bundledDirectory.isBlank());
    }

    public LocalSearchModel(String dataDirectory) { this(dataDirectory, (Path) null, false); }

    public LocalSearchModel(String dataDirectory, Path bundledDirectory, boolean automatic) {
        this.automatic = automatic;
        directory = automatic ? java.util.Objects.requireNonNull(bundledDirectory) : Path.of(dataDirectory).resolve("search-model-e5-v1");
        if (automatic) {
            status = new Status("STARTING", 0, total(), null);
            return;
        }
        try {
            boolean complete = true;
            for (var asset : ASSETS) complete &= valid(asset);
            if (complete) { load(); status = new Status("READY", total(), total(), null); }
        } catch (Exception | LinkageError exception) {
            status = new Status("ERROR", 0, total(), "검색 엔진을 준비하지 못했습니다. 다시 시도해 주세요.");
        }
    }

    private long total() { return ASSETS.stream().mapToLong(Asset::size).sum(); }
    public Status status() { return status; }
    public boolean automatic() { return automatic; }
    public boolean preparing() { return automatic && !"READY".equals(status.state()) || Set.of("DOWNLOADING", "LOADING", "INDEXING").contains(status.state()); }
    public synchronized boolean ready() { return session != null && tokenizer != null && reranker != null && rerankTokenizer != null; }

    public synchronized void prepare(Runnable rebuild) {
        if (Set.of("DOWNLOADING", "LOADING", "INDEXING").contains(status.state())) return;
        cancelled = false;
        status = new Status(automatic ? "LOADING" : "DOWNLOADING", 0, total(), null);
        worker.submit(() -> {
            try {
                if (automatic) {
                    for (var asset : ASSETS) if (!valid(asset)) throw new IOException("Bundled search asset failed verification");
                } else {
                    Files.createDirectories(directory);
                    long completed = 0;
                    for (var asset : ASSETS) { download(asset, completed); completed += asset.size(); }
                }
                checkCancelled();
                status = new Status("LOADING", total(), total(), null);
                synchronized (this) { if (!ready()) load(); }
                status = new Status("INDEXING", total(), total(), null);
                rebuild.run();
                status = new Status("READY", total(), total(), null);
            } catch (Exception | Error exception) {
                status = new Status(cancelled ? "NOT_READY" : "ERROR", 0, total(), cancelled ? null
                        : automatic ? "검색 엔진을 준비하지 못했습니다. 다시 시도하거나 앱을 다시 설치해 주세요."
                        : "검색 준비에 실패했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.");
                org.slf4j.LoggerFactory.getLogger(LocalSearchModel.class).warn("Search engine preparation failed: {} at {}",
                        exception.getClass().getName(), exception.getStackTrace().length == 0 ? "unknown" : exception.getStackTrace()[0]);
                if (exception instanceof VirtualMachineError fatal) throw fatal;
                if (exception instanceof ThreadDeath fatal) throw fatal;
            }
        });
    }

    public void cancel() { if ("DOWNLOADING".equals(status.state())) cancelled = true; }
    private void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
    }

    private boolean valid(Asset asset) throws Exception {
        Path file = directory.resolve(asset.local());
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) != asset.size()) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest()).equals(asset.hash());
    }

    private void download(Asset asset, long completed) throws Exception {
        if (valid(asset)) { status = new Status("DOWNLOADING", completed + asset.size(), total(), null); return; }
        Path temporary = Files.createTempFile(directory, "download-", ".part");
        try (var client = org.apache.hc.client5.http.impl.classic.HttpClients.custom().disableRedirectHandling()
                .disableCookieManagement().disableAutomaticRetries().setDefaultRequestConfig(
                        org.apache.hc.client5.http.config.RequestConfig.custom()
                                .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(10))
                                .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(30)).build()).build()) {
            URI uri = URI.create(asset.remote().startsWith("https://") ? asset.remote() : BASE + asset.remote());
            boolean downloaded = false;
            for (int redirects = 0; redirects <= 5; redirects++) {
                checkCancelled();
                if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())
                        || uri.getRawUserInfo() != null || uri.getPort() != -1) throw new IOException("Untrusted model origin");
                URI current = uri;
                URI next = client.execute(new org.apache.hc.client5.http.classic.methods.HttpGet(uri), response -> {
                    if (response.getCode() >= 300 && response.getCode() < 400) {
                        var location = response.getFirstHeader("Location");
                        if (location == null) throw new IOException("Missing model redirect");
                        return current.resolve(location.getValue());
                    }
                    if (response.getCode() != 200 || response.getEntity() == null) throw new IOException("Model download failed");
                    if (response.getEntity().getContentLength() > asset.size()) throw new IOException("Unexpected model size");
                    long size = 0;
                    long deadline = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
                    try (var input = response.getEntity().getContent(); var output = Files.newOutputStream(temporary)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int count; (count = input.read(buffer)) != -1;) {
                            checkCancelled();
                            size += count;
                            if (size > asset.size() || System.nanoTime() > deadline) throw new IOException("Model download budget exceeded");
                            output.write(buffer, 0, count);
                            status = new Status("DOWNLOADING", completed + size, total(), null);
                        }
                    }
                    if (size != asset.size()) throw new IOException("Incomplete model");
                    return null;
                });
                if (next == null) { downloaded = true; break; }
                uri = next;
            }
            if (!downloaded) throw new IOException("Too many model redirects");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(asset.hash())) throw new IOException("Model checksum mismatch");
            Files.move(temporary, directory.resolve(asset.local()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void load() throws Exception {
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
            options.setInterOpNumThreads(1);
            var opened = OrtEnvironment.getEnvironment().createSession(directory.resolve(MODEL.local()).toString(), options);
            try {
                tokenizer = HuggingFaceTokenizer.newInstance(directory.resolve(TOKENIZER.local()),
                        Map.of("maxLength", "512", "truncation", "true", "padding", "false"));
                reranker = OrtEnvironment.getEnvironment().createSession(directory.resolve(RERANKER.local()).toString(), options);
                rerankTokenizer = HuggingFaceTokenizer.newInstance(directory.resolve(RERANK_TOKENIZER.local()),
                        Map.of("maxLength", "512", "truncation", "true", "padding", "false"));
                session = opened;
            } catch (Exception | Error exception) {
                opened.close();
                if (tokenizer != null) { tokenizer.close(); tokenizer = null; }
                if (reranker != null) { reranker.close(); reranker = null; }
                if (rerankTokenizer != null) { rerankTokenizer.close(); rerankTokenizer = null; }
                throw exception;
            }
        }
    }

    public synchronized float[] encode(String text, boolean question) {
        if (!ready()) return null;
        var encoded = tokenizer.encode((question ? "query: " : "passage: ") + text);
        long[] ids = encoded.getIds();
        long[] mask = encoded.getAttentionMask();
        long[] types = encoded.getTypeIds();
        try (var tokens = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][] {ids});
             var attention = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][] {mask});
             var segments = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][] {types})) {
            var inputs = new java.util.HashMap<String, OnnxTensor>();
            inputs.put("input_ids", tokens); inputs.put("attention_mask", attention);
            if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", segments);
            try (var result = session.run(inputs)) {
                float[][] output = ((float[][][]) result.get(0).getValue())[0];
                float[] vector = new float[output[0].length];
                for (int token = 0; token < mask.length; token++) {
                    if (mask[token] == 0) continue;
                    for (int dimension = 0; dimension < vector.length; dimension++) vector[dimension] += output[token][dimension];
                }
                double norm = 0;
                for (float value : vector) norm += value * value;
                norm = Math.sqrt(norm);
                if (norm == 0 || !Double.isFinite(norm)) throw new IllegalStateException("Invalid embedding");
                for (int dimension = 0; dimension < vector.length; dimension++) vector[dimension] /= (float) norm;
                return vector;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("검색용 문장 분석에 실패했습니다.", exception);
        }
    }

    @PreDestroy
    public synchronized void close() throws Exception {
        cancelled = true;
        worker.shutdownNow();
        if (tokenizer != null) { tokenizer.close(); tokenizer = null; }
        if (session != null) { session.close(); session = null; }
        if (rerankTokenizer != null) { rerankTokenizer.close(); rerankTokenizer = null; }
        if (reranker != null) { reranker.close(); reranker = null; }
    }

    public synchronized float relevance(String question, String text) {
        if (!ready()) return Float.NaN;
        var encoding = rerankTokenizer.encode(question, text);
        try (var tokens = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][] {encoding.getIds()});
             var mask = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][] {encoding.getAttentionMask()})) {
            try (var result = reranker.run(Map.of("input_ids", tokens, "attention_mask", mask))) {
                float logit = ((float[][]) result.get(0).getValue())[0][0];
                return (float) (1 / (1 + Math.exp(-logit)));
            }
        } catch (Exception exception) { throw new IllegalStateException("검색 결과의 관련성을 확인하지 못했습니다.", exception); }
    }
}
