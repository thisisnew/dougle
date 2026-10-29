package dev.dougle.search.adapter;

import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.domain.ContentDigest;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LuceneSearchIndex implements SearchIndex {
    private static final FieldType BODY = bodyFieldType();
    private static final java.util.regex.Pattern TECHNICAL_QUESTION = java.util.regex.Pattern.compile(
            "(?iu)코드|인터페이스|생성자|함수|메서드|필드|호출|구현|실행|클래스|소스|알고리즘|패턴|테이블|\\b(?:api|code|class|interface|constructor|method|field|function|implementation)\\b");

    private static FieldType bodyFieldType() {
        var type = new FieldType(TextField.TYPE_STORED);
        type.setStoreTermVectors(true);
        type.setStoreTermVectorOffsets(true);
        type.freeze();
        return type;
    }

    private final KoreanAnalyzer analyzer = new KoreanAnalyzer();
    private final FSDirectory directory;
    private final IndexWriter writer;
    private final dev.dougle.search.application.TextEmbedding embeddings;
    private String format;

    public LuceneSearchIndex(String dataDirectory) throws IOException {
        this(dataDirectory, dev.dougle.search.application.TextEmbedding.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LuceneSearchIndex(@Value("${forge.data-dir}") String dataDirectory,
                            dev.dougle.search.application.TextEmbedding embeddings) throws IOException {
        this.embeddings = embeddings;
        format = expectedFormat();
        Path path = Path.of(dataDirectory).resolve("lucene");
        Files.createDirectories(path);
        directory = FSDirectory.open(path);
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    }

    public void reset() {
        format = expectedFormat();
        try {
            writer.deleteAll();
            writer.setLiveCommitData(java.util.Map.of("dougle-format", "building").entrySet());
            writer.commit();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private String expectedFormat() { return embeddings.ready() ? "chunks-v3-e5" : "chunks-v1-keyword"; }

    public boolean questionReady() { return !embeddings.preparing(); }

    public boolean initialized() {
        try {
            if (!DirectoryReader.indexExists(directory)) return false;
            try (var reader = DirectoryReader.open(directory)) {
                boolean matches = expectedFormat().equals(reader.getIndexCommit().getUserData().get("dougle-format"));
                if (matches) format = expectedFormat();
                return matches;
            }
        } catch (IOException exception) { return false; }
    }

    public void put(SearchIndex.Document value) {
        String textHash = value.textHash();
        float[] titleVector = embeddings.ready() ? embeddings.encode((value.path() + " " + value.name())
                .replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[^\\p{L}\\p{N}]+", " "), false) : null;
        Iterable<org.apache.lucene.document.Document> documents = () -> {
            var chunks = value.chunks(embeddings.ready() ? 768 : 2400).iterator();
            return new java.util.Iterator<>() {
                public boolean hasNext() { return chunks.hasNext(); }
                public org.apache.lucene.document.Document next() {
                    var chunk = chunks.next();
                    dev.dougle.ingestion.application.ProcessingProgress.report("SPLITTING", value.name(),
                            chunk.offset(), value.textParts().isEmpty() ? value.text().length() : 0);
                    return document(value, chunk, textHash, titleVector);
                }
            };
        };
        try { writer.updateDocuments(new Term("id", value.id()), documents); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private org.apache.lucene.document.Document document(SearchIndex.Document value,
            dev.dougle.search.domain.TextChunks.Chunk chunk, String textHash, float[] titleVector) {
        var document = new org.apache.lucene.document.Document();
        document.add(new StringField("id", value.id(), Field.Store.YES));
        document.add(new StringField("workspace", value.workspaceId(), Field.Store.NO));
        for (String domain : value.domains()) document.add(new StringField("domain", domain, Field.Store.YES));
        document.add(new StringField("kind", value.kind(), Field.Store.YES));
        document.add(new StoredField("textHash", textHash));
        document.add(new StoredField("startLine", chunk.startLine()));
        if (value.cardId() != null) {
            document.add(new StoredField("card", value.cardId()));
            document.add(new StoredField("version", value.version()));
        }
        document.add(new StoredField("source", value.sourceId() == null ? "" : value.sourceId()));
        document.add(new StoredField("path", value.path()));
        document.add(new TextField("name", value.name(), Field.Store.YES));
        document.add(new Field("text", chunk.text(), BODY));
        document.add(new TextField("symbols", value.symbols(), Field.Store.NO));
        if (titleVector != null) {
            var bytes = java.nio.ByteBuffer.allocate(titleVector.length * Float.BYTES);
            for (float component : titleVector) bytes.putFloat(component);
            document.add(new StoredField("meaningTitleVector", bytes.array()));
        }
        if (embeddings.ready() && !chunk.text().isBlank()) {
            float[] vector = embeddings.encode(SearchRepresentation.of(value.path(), chunk.text()), false);
            if (vector != null) document.add(new org.apache.lucene.document.KnnFloatVectorField("meaning", vector,
                    org.apache.lucene.index.VectorSimilarityFunction.COSINE));
        }
        return document;
    }

    public void remove(String id) {
        try { writer.deleteDocuments(new Term("id", id)); } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public void commit() {
        try {
            writer.setLiveCommitData(java.util.Map.of("dougle-format", format).entrySet());
            writer.commit();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public List<Hit> search(String workspaceId, String question, List<String> domains, boolean includeKnowledge) {
        return search(workspaceId, question, domains, includeKnowledge, true);
    }

    public List<Hit> searchQuestion(String workspaceId, String question, List<String> domains, boolean includeKnowledge) {
        var lexical = search(workspaceId, question, domains, includeKnowledge, false);
        if (!embeddings.ready()) return lexical;
        var semantic = semantic(workspaceId, question, domains, includeKnowledge);
        var hits = new java.util.LinkedHashMap<String, Hit>();
        var repetitions = new java.util.HashMap<String, Integer>();
        for (Hit hit : semantic) {
            String structure = hit.snippet().replaceAll("\\p{N}+", "#").replaceAll("\\s+", " ");
            if (repetitions.merge(structure, 1, Integer::sum) > 2) continue;
            String key = hit.kind() + ":" + (hit.entryId() == null ? hit.cardId() : hit.entryId()) + ":" + hit.startLine();
            hits.put(key, hit);
            if (hits.size() >= 24) break;
        }
        for (Hit hit : lexical.stream().limit(8).toList()) {
            String key = hit.kind() + ":" + (hit.entryId() == null ? hit.cardId() : hit.entryId()) + ":" + hit.startLine();
            hits.putIfAbsent(key, hit);
        }
        var candidates = hits.values();
        var reranked = new java.util.ArrayList<Hit>();
        for (Hit hit : candidates) {
            float score = embeddings.relevance(question, SearchRepresentation.evidence(hit.path(), hit.snippet()));
            if (!Float.isFinite(score)) score = hit.score();
            reranked.add(new Hit(hit.entryId(), hit.name(), hit.sourceId(), hit.domain(), hit.path(), hit.snippet(), score,
                    hit.startLine(), hit.endLine(), hit.bodyMatch(), hit.kind(), hit.cardId(), hit.version(), hit.domains(),
                    hit.freshness(), hit.textHash()));
        }
        reranked.sort(java.util.Comparator.comparingDouble(Hit::score).reversed());
        float best = reranked.isEmpty() ? 0 : reranked.getFirst().score();
        var semanticScores = new java.util.HashMap<String, Float>();
        for (Hit hit : semantic) {
            semanticScores.merge(hit.kind() + ":" + (hit.entryId() == null ? hit.cardId() : hit.entryId()) + ":" + hit.startLine(), hit.score(), Math::max);
        }
        Set<String> included = new HashSet<>();
        return reranked.stream().filter(hit -> hit.score() >= best * 0.10f && relevant(question, hit,
                        semanticScores.getOrDefault(hit.kind() + ":" + (hit.entryId() == null ? hit.cardId() : hit.entryId()) + ":" + hit.startLine(), 0f)))
                .filter(hit -> included.add(hit.kind() + ":" + (hit.entryId() == null ? hit.cardId() : hit.entryId()))).toList();
    }

    private boolean relevant(String question, Hit hit, float similarity) {
        if (hit.score() >= 0.5f) return true;
        if (hit.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
            boolean technical = TECHNICAL_QUESTION.matcher(question).find();
            return hit.score() >= 0.0001f && similarity >= 0.90f
                    || technical && hit.score() >= 0.005f && similarity >= 0.88f;
        }
        return hit.score() >= 0.03f && similarity >= 0.89f || hit.score() >= 0.005f && similarity >= 0.91f;
    }

    private List<Hit> semantic(String workspaceId, String question, List<String> domains, boolean includeKnowledge) {
        float[] vector = embeddings.encode(QuestionFocus.of(question), true);
        if (vector == null) return List.of();
        var filter = new BooleanQuery.Builder();
        filter.add(new TermQuery(new Term("workspace", workspaceId)), BooleanClause.Occur.FILTER);
        if (!includeKnowledge) filter.add(new TermQuery(new Term("kind", "ORIGINAL")), BooleanClause.Occur.FILTER);
        if (!domains.isEmpty()) filter.add(new TermInSetQuery("domain", domains.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER);
        try (var reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            var matches = searcher.search(new org.apache.lucene.search.KnnFloatVectorQuery("meaning", vector, 128, filter.build()), 128);
            List<Hit> results = new ArrayList<>();
            for (var match : matches.scoreDocs) {
                var document = searcher.storedFields().document(match.doc);
                float relevance = match.score;
                var title = document.getBinaryValue("meaningTitleVector");
                if (title != null && title.length == vector.length * Float.BYTES) {
                    var bytes = java.nio.ByteBuffer.wrap(title.bytes, title.offset, title.length);
                    float cosine = 0;
                    for (float component : vector) cosine += component * bytes.getFloat();
                    relevance = match.score * 0.75f + (1 + cosine) / 2 * 0.25f;
                }
                String text = document.get("text");
                int line = document.getField("startLine").numericValue().intValue();
                int endLine = line + (int) text.chars().limit(Math.max(0, text.length() - 1)).filter(value -> value == '\n').count();
                boolean published = "KNOWLEDGE".equals(document.get("kind"));
                var resultDomains = List.of(document.getValues("domain"));
                results.add(new Hit(published ? null : document.get("id"), document.get("name"), document.get("source"),
                        String.join(", ", resultDomains), document.get("path"), text, relevance, line, endLine, true,
                        document.get("kind"), document.get("card"), published ? document.getField("version").numericValue().intValue() : null,
                        resultDomains, "UNCHECKED", document.get("textHash")));
            }
            results.sort(java.util.Comparator.comparingDouble(Hit::score).reversed());
            return results;
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private List<Hit> search(String workspaceId, String question, List<String> domains, boolean includeKnowledge, boolean requireAllTerms) {
        try (var tokens = analyzer.tokenStream("text", question);
             var reader = DirectoryReader.open(writer)) {
            var terms = tokens.addAttribute(CharTermAttribute.class);
            var query = new BooleanQuery.Builder();
            query.add(new TermQuery(new Term("workspace", workspaceId)), BooleanClause.Occur.FILTER);
            if (!includeKnowledge) query.add(new TermQuery(new Term("kind", "ORIGINAL")), BooleanClause.Occur.FILTER);
            if (!domains.isEmpty()) {
                query.add(new TermInSetQuery("domain", domains.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER);
            }
            int count = 0;
            Set<String> queryTerms = new HashSet<>();
            tokens.reset();
            while (tokens.incrementToken() && count < 32) {
                if (!queryTerms.add(terms.toString())) continue;
                var termQuery = new BooleanQuery.Builder();
                for (String field : List.of("name", "text", "symbols")) {
                    termQuery.add(new TermQuery(new Term(field, terms.toString())), BooleanClause.Occur.SHOULD);
                }
                query.add(termQuery.build(), requireAllTerms ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
                count++;
            }
            tokens.end();
            if (count == 0) return List.of();
            if (!requireAllTerms) query.setMinimumNumberShouldMatch(1);
            var searcher = new IndexSearcher(reader);
            var matches = searcher.search(query.build(), 100);
            List<Hit> results = new ArrayList<>();
            Set<String> included = new HashSet<>();
            for (var match : matches.scoreDocs) {
                var document = searcher.storedFields().document(match.doc);
                if ((requireAllTerms || !embeddings.ready()) && !included.add(document.get("id"))) continue;
                String text = document.get("text");
                int offset = bestBodyOffset(reader, match.doc, queryTerms);
                int start = requireAllTerms ? Math.max(0, offset - 70) : 0;
                if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
                int end = Math.min(text.length(), start + (requireAllTerms ? 320 : 2400));
                if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
                String snippet = text.substring(start, end);
                int baseLine = document.getField("startLine") == null ? 1 : document.getField("startLine").numericValue().intValue();
                int startLine = text.isEmpty() ? 0 : baseLine + (int) text.substring(0, start).chars().filter(value -> value == '\n').count();
                int endLine = startLine + (int) snippet.chars().limit(Math.max(0, snippet.length() - 1)).filter(value -> value == '\n').count();
                boolean published = "KNOWLEDGE".equals(document.get("kind"));
                var resultDomains = List.of(document.getValues("domain"));
                results.add(new Hit(published ? null : document.get("id"), document.get("name"), document.get("source"),
                        String.join(", ", resultDomains), document.get("path"), snippet, match.score, startLine, endLine,
                        offset >= 0, document.get("kind"), document.get("card"),
                        published ? document.getField("version").numericValue().intValue() : null,
                        resultDomains, "UNCHECKED", document.get("textHash")));
                if (results.size() == 30) break;
            }
            return results;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private int bestBodyOffset(DirectoryReader reader, int documentId, Set<String> queryTerms) throws IOException {
        var vectors = reader.termVectors().get(documentId, "text");
        if (vectors == null) return -1;
        var iterator = vectors.iterator();
        record Occurrence(String term, int offset) {}
        var occurrences = new ArrayList<Occurrence>();
        for (String term : queryTerms) {
            if (!iterator.seekExact(new BytesRef(term))) continue;
            var positions = iterator.postings(null, PostingsEnum.OFFSETS);
            positions.nextDoc();
            for (int occurrence = 0; occurrence < positions.freq(); occurrence++) {
                positions.nextPosition();
                int offset = positions.startOffset();
                if (offset >= 0) occurrences.add(new Occurrence(term, offset));
            }
        }
        occurrences.sort(java.util.Comparator.comparingInt(Occurrence::offset));
        int bestOffset = -1;
        int bestCount = 0;
        int bestSpan = Integer.MAX_VALUE;
        for (int start = 0; start < occurrences.size(); start++) {
            var matched = new HashSet<String>();
            int offset = occurrences.get(start).offset();
            for (int end = start; end < occurrences.size(); end++) {
                var occurrence = occurrences.get(end);
                int span = occurrence.offset() - offset;
                if (span > 240) break;
                matched.add(occurrence.term());
                if (matched.size() > bestCount || matched.size() == bestCount && span < bestSpan) {
                    bestCount = matched.size();
                    bestSpan = span;
                    bestOffset = offset;
                }
            }
        }
        return bestOffset;
    }

    @PreDestroy
    public void close() throws IOException {
        writer.close();
        directory.close();
        analyzer.close();
    }
}
