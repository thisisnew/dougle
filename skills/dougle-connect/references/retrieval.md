# Retrieval detail

## Focused retrieval and a compact topic index

Maintain a compact navigation table in index.md when connecting, refreshing or merging knowledge: topic label, established Korean/English aliases and code identifiers, topic-note link, relevant source IDs and useful original file/symbol or document-section locators. This table contains pointers, not full evidence or a second copy of every claim. Update only affected entries after their notes are successfully saved; retain canonical identities and do not map unrelated homonyms to the same topic. Imported entries must resolve to the recipient's local mappings and stay unverified until checked. Deactivated sources must not be selected as active evidence.

Older collections need no full rebuild before answering. Use existing indexes/notes, then add or repair pointers for the topics actually investigated. Missing/broken/stale pointers fall back to scoped search, never to "no such information". Do not fabricate aliases or prove a relationship from a table entry alone.

Retrieval order for ask:

1. Use the current conversation's subject, explicit business scope and established aliases in the common store to identify a small initial candidate set (typically 1–3 topic notes). Read only relevant source records/coverage. Reuse unchanged material already in active context rather than calling the same reader repeatedly.
2. Follow exact original locators from those notes. Search a bounded relevant directory/document scope with a few useful aliases together when supported; prefer file/symbol matches over loading every file. Existing host tools such as file search or rg may be used, but do not require or install a new engine.
3. Batch or run independent read-only lookups concurrently only if the host supports it and access/rate limits permit. Keep dependent steps sequential and all memory writes serialized. Do not spawn other models to search.
4. Apply the freshness policy only to coverage the answer depends on. A recent result is reusable only while its evidence, scope and invalidation status remain valid. An unchanged revision check can avoid rereading known content; a source change, explicit latest request or conflict overrides the fast path.
5. Stop when the requested answer is supported. If evidence is absent, ambiguous or conflicting, widen within authorized scope and include alternative candidates. A first-batch miss is not evidence of absence; an exhaustive question must not be capped at three notes. Preserve remaining coverage/uncertainty if tools fail or the host budget ends.

The aim is fewer unnecessary reads, not a fixed speed promise. If investigating slowness, distinguish source discovery, source I/O/refresh and answer generation using available timing data; never invent timings or claim a speedup without measurement. A faster full-text index alone does not accelerate remote authentication, document download or model generation. No Lucene, vector store, background indexer or answer cache is installed by this protocol.

