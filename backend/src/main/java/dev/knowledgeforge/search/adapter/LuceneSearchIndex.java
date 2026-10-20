package dev.knowledgeforge.search.adapter;

import dev.knowledgeforge.search.application.SearchIndex;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
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
        document.add(new TextField("text", value.text(), Field.Store.YES));
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
            tokens.reset();
            while (tokens.incrementToken() && count < 32) {
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
                int start = Math.max(0, text.toLowerCase(Locale.ROOT).indexOf(question.toLowerCase(Locale.ROOT)) - 70);
                String snippet = text.substring(start, Math.min(text.length(), start + 320));
                results.add(new Hit(document.get("id"), document.get("name"), document.get("source"),
                        document.get("domain"), document.get("path"), snippet, match.score));
            }
            return results;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @PreDestroy
    public void close() throws IOException {
        writer.close();
        directory.close();
        analyzer.close();
    }
}
