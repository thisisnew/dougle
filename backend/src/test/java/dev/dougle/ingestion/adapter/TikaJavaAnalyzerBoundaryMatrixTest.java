package dev.dougle.ingestion.adapter;

import dev.dougle.ingestion.application.ProcessingProgress;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;
import static dev.dougle.ingestion.adapter.LocalFolderIngestionMatrixTest.*;

class TikaJavaAnalyzerBoundaryMatrixTest {
    enum Invalid {
        TRUNCATED_UTF8, OVERLONG_UTF8, NUL, ODD_UTF16_LE, ODD_UTF16_BE, BINARY;
        byte[] bytes() {
            return switch (this) {
                case TRUNCATED_UTF8 -> new byte[]{'a', (byte) 0xe2, (byte) 0x82};
                case OVERLONG_UTF8 -> new byte[]{'a', (byte) 0xc0, (byte) 0xaf};
                case NUL -> new byte[]{'a', 0, 'b'};
                case ODD_UTF16_LE -> new byte[]{(byte) 0xff, (byte) 0xfe, 'a', 0, 'b'};
                case ODD_UTF16_BE -> new byte[]{(byte) 0xfe, (byte) 0xff, 0, 'a', 0};
                case BINARY -> new byte[]{'P', 'K', 3, 4, 7, 8};
            };
        }
    }

    static Stream<Arguments> invalidInputs() {
        return sources().stream().flatMap(source -> Stream.of(Invalid.values())
                .map(invalid -> Arguments.of(source.name(), invalid)));
    }

    @ParameterizedTest(name = "TAB-INVALID-{index}: source={0} invalid={1}")
    @MethodSource("invalidInputs")
    void rejectsInvalidEncodingAndBinaryWithoutPartialText(String name, Invalid invalid) {
        byte[] content = invalid.bytes();
        byte[] original = content.clone();
        try (var analysis = new TikaJavaAnalyzer().analyze(name, content)) {
            assertThat(analysis.status()).isEqualTo("ERROR");
            assertThat(analysis.error()).contains("인코딩");
            assertThat(analysis.text()).isEmpty();
            assertThat(analysis.parts()).isEmpty();
            assertThat(analysis.symbols()).isEmpty();
        }
        assertThat(content).containsExactly(original);
    }

    static Stream<Arguments> blankInputs() {
        return sources().stream().flatMap(source -> Stream.of(Encoding.values())
                .map(encoding -> Arguments.of(source.name(), encoding)));
    }

    @ParameterizedTest(name = "TAB-BLANK-{index}: source={0} encoding={1}")
    @MethodSource("blankInputs")
    void blankBomTextIsUnsupportedInsteadOfReady(String name, Encoding encoding) {
        byte[] content = encoding.bytes(" \r\n\t\r\f\n");
        byte[] original = content.clone();
        try (var analysis = new TikaJavaAnalyzer().analyze(name, content)) {
            assertThat(analysis.status()).isEqualTo("UNSUPPORTED");
            assertThat(analysis.text()).isEmpty();
            assertThat(analysis.symbols()).isEmpty();
            assertThat(analysis.error()).contains("텍스트");
        }
        assertThat(content).containsExactly(original);
    }

    static Stream<Arguments> extractionLimits() {
        return Stream.of(Encoding.values()).flatMap(encoding -> Stream.of("a", "한", "😀")
                .flatMap(marker -> Stream.of(-1, 0, 1).map(delta -> Arguments.of(encoding, marker, delta))));
    }

    @ParameterizedTest(name = "TAB-BUDGET-{index}: encoding={0} marker={1} budgetDelta={2}")
    @MethodSource("extractionLimits")
    void extractionBudgetCountsUtf8OutputBytes(Encoding encoding, String marker, int delta) {
        String expected = "x".repeat(256 + delta - marker.getBytes(StandardCharsets.UTF_8).length) + marker;
        byte[] content = encoding.bytes(expected);
        byte[] original = content.clone();
        try (var analysis = new TikaJavaAnalyzer(256).analyze("synthetic.txt", content)) {
            assertThat(analysis.status()).isEqualTo(delta > 0 ? "ERROR" : "READY");
            assertThat(analysis.text()).isEqualTo(delta > 0 ? "" : expected);
            if (delta > 0) assertThat(analysis.error()).contains("256", "한도");
            else assertThat(analysis.error()).isNull();
            assertThat(analysis.symbols()).isEmpty();
        }
        assertThat(content).containsExactly(original);
    }

    static Stream<Arguments> readBoundaries() {
        return Stream.of(Encoding.values()).flatMap(encoding ->
                Stream.of(8191, 8192, 8193, 16383, 16384, 16385, 65535, 65536, 65537)
                        .flatMap(length -> Stream.of(1, 3, 4093)
                                .map(fragment -> Arguments.of(encoding, length, fragment))));
    }

    @ParameterizedTest(name = "TAB-STREAM-{index}: encoding={0} characters={1} fragmentBytes={2}")
    @MethodSource("readBoundaries")
    void preservesUnicodeAndCrLfAcrossReadBoundaries(Encoding encoding, int length, int fragment) throws Exception {
        String prefix = "x".repeat(length - 1);
        byte[] content = encoding.bytes(prefix + "\r\n한😀e\u0301");
        byte[] original = content.clone();
        var reads = new AtomicInteger();
        var bytesRead = new AtomicInteger();
        try (var input = new java.io.FilterInputStream(new ByteArrayInputStream(content)) {
            @Override public int read(byte[] buffer, int offset, int requested) throws java.io.IOException {
                int count = super.read(buffer, offset, Math.min(requested, fragment));
                if (count > 0) {
                    reads.incrementAndGet();
                    bytesRead.addAndGet(count);
                }
                return count;
            }
        }; var analysis = new TikaJavaAnalyzer().analyzeStream("synthetic.txt", input)) {
            assertThat(analysis.status()).isEqualTo("READY");
            assertThat(String.join("", analysis.textParts())).isEqualTo(prefix + "\n한😀é");
            assertThat(analysis.error()).isNull();
        }
        assertThat(bytesRead.get()).isEqualTo(content.length);
        assertThat(reads.get()).isGreaterThanOrEqualTo((content.length + fragment - 1) / fragment);
        assertThat(content).containsExactly(original);
    }

    static Stream<Arguments> cancellations() {
        return sources().stream().flatMap(source -> Stream.of("BEFORE_READ", "DURING_EXTRACTION")
                .map(phase -> Arguments.of(source.name(), phase)));
    }

    @ParameterizedTest(name = "TAB-CANCEL-{index}: source={0} phase={1}")
    @MethodSource("cancellations")
    void cancellationDoesNotProduceAnAnalysisCheckpoint(String name, String phase) throws Exception {
        byte[] content = "synthetic cancellation\n".repeat(4000).getBytes(StandardCharsets.UTF_8);
        byte[] original = content.clone();
        var reads = new AtomicInteger();
        var analyzer = new TikaJavaAnalyzer();
        try (var input = new ByteArrayInputStream(content) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                reads.incrementAndGet();
                return super.read(buffer, offset, length);
            }
        }) {
            if (phase.equals("BEFORE_READ")) {
                try {
                    Thread.currentThread().interrupt();
                    assertThatThrownBy(() -> analyzer.analyzeStream(name, input)).isInstanceOf(CancellationException.class);
                    assertThat(reads.get()).isZero();
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally {
                    Thread.interrupted();
                }
            } else {
                var events = new AtomicInteger();
                try (var observation = ProcessingProgress.observe(step -> {
                    if (step.stage().equals("EXTRACTING_CHARS")) {
                        events.incrementAndGet();
                        throw new CancellationException("synthetic cancellation");
                    }
                })) {
                    assertThatThrownBy(() -> analyzer.analyzeStream(name, input)).isInstanceOf(CancellationException.class);
                }
                assertThat(events.get()).isEqualTo(1);
                assertThat(reads.get()).isPositive();
            }
        }
        assertThat(content).containsExactly(original);
        try (var recovered = analyzer.analyze("recovered.txt", "recovered synthetic text".getBytes(StandardCharsets.UTF_8))) {
            assertThat(recovered.status()).isEqualTo("READY");
            assertThat(recovered.text()).isEqualTo("recovered synthetic text");
        }
    }
}
