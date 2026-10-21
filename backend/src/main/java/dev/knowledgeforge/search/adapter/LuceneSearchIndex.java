package dev.knowledgeforge.search.adapter;

import dev.knowledgeforge.search.application.SearchIndex;
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

    public LuceneSearchIndex(@Value("${forge.data-dir}") String dataDirectory) throws IOException {
        Path path = Path.of(dataDirectory).resolve("lucene");
        Files.createDirectories(path);
        directory = FSDirectory.open(path);
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    }

    public void reset() {
        try { writer.deleteAll(); } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public void put(SearchIndex.Document value) {
        var document = new org.apache.lucene.document.Document();
        document.add(new StringField("id", value.id(), Field.Store.YES));
        document.add(new StringField("workspace", value.workspaceId(), Field.Store.NO));
        document.add(new StringField("domain", value.domain(), Field.Store.YES));
        document.add(new StoredField("source", value.sourceId() == null ? "" : value.sourceId()));
        document.add(new StoredField("path", value.path()));
        document.add(new TextField("name", value.name(), Field.Store.YES));
        document.add(new Field("text", value.text(), BODY));
        document.add(new TextField("symbols", value.symbols(), Field.Store.NO));
        try { writer.updateDocument(new Term("id", value.id()), document); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public void remove(String id) {
        try { writer.deleteDocuments(new Term("id", id)); } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public void commit() {
        try { writer.commit(); } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public List<Hit> search(String workspaceId, String question, List<String> domains) {
        try (var tokens = analyzer.tokenStream("text", question);
             var reader = DirectoryReader.open(writer)) {
            var terms = tokens.addAttribute(CharTermAttribute.class);
            var query = new BooleanQuery.Builder();
            query.add(new TermQuery(new Term("workspace", workspaceId)), BooleanClause.Occur.FILTER);
            if (!domains.isEmpty()) {
                query.add(new TermInSetQuery("domain", domains.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER);
            }
            int count = 0;
            Set<String> queryTerms = new HashSet<>();
            tokens.reset();
            while (tokens.incrementToken() && count < 32) {
                queryTerms.add(terms.toString());
                var termQuery = new BooleanQuery.Builder();
                for (String field : List.of("name", "text", "symbols")) {
                    termQuery.add(new TermQuery(new Term(field, terms.toString())), BooleanClause.Occur.SHOULD);
                }
                query.add(termQuery.build(), BooleanClause.Occur.MUST);
                count++;
            }
            tokens.end();
            if (count == 0) return List.of();
            var searcher = new IndexSearcher(reader);
            var matches = searcher.search(query.build(), 30);
            List<Hit> results = new ArrayList<>();
            for (var match : matches.scoreDocs) {
                var document = searcher.storedFields().document(match.doc);
                String text = document.get("text");
                int offset = firstBodyOffset(reader, match.doc, queryTerms);
                int start = Math.max(0, offset - 70);
                if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
                int end = Math.min(text.length(), start + 320);
                if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
                String snippet = text.substring(start, end);
                int startLine = text.isEmpty() ? 0 : 1 + (int) text.substring(0, start).chars().filter(value -> value == '\n').count();
                int endLine = startLine + (int) snippet.chars().limit(Math.max(0, snippet.length() - 1)).filter(value -> value == '\n').count();
                results.add(new Hit(document.get("id"), document.get("name"), document.get("source"),
                        document.get("domain"), document.get("path"), snippet, match.score, startLine, endLine, offset >= 0));
            }
            return results;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private int firstBodyOffset(DirectoryReader reader, int documentId, Set<String> queryTerms) throws IOException {
        var vectors = reader.termVectors().get(documentId, "text");
        if (vectors == null) return -1;
        var iterator = vectors.iterator();
        int first = Integer.MAX_VALUE;
        for (String term : queryTerms) {
            if (!iterator.seekExact(new BytesRef(term))) continue;
            var positions = iterator.postings(null, PostingsEnum.OFFSETS);
            positions.nextDoc();
            for (int occurrence = 0; occurrence < positions.freq(); occurrence++) {
                positions.nextPosition();
                int offset = positions.startOffset();
                if (offset >= 0) first = Math.min(first, offset);
            }
        }
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    @PreDestroy
    public void close() throws IOException {
        writer.close();
        directory.close();
        analyzer.close();
    }
}
