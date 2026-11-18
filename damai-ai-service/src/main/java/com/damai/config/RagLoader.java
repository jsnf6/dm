package com.damai.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class RagLoader {

    private static final Logger log = LoggerFactory.getLogger(RagLoader.class);

    private final VectorStore vectorStore;

    public RagLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void loadDocuments() {
        try {
            List<Document> existing = vectorStore.similaritySearch(
                    SearchRequest.builder().query("test").topK(1).build());
            if (!existing.isEmpty()) {
                log.info("RAG documents already loaded, skipping.");
                return;
            }
        } catch (Exception e) {
            log.info("Redis store appears empty or unreachable, loading documents...");
        }

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:doc/*.txt");

            List<Document> allDocs = new ArrayList<>();
            for (Resource res : resources) {
                TextReader reader = new TextReader(res);
                List<Document> docs = reader.get();
                allDocs.addAll(docs);
                log.info("Read document: {}", res.getFilename());
            }

            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> chunks = splitter.apply(allDocs);

            vectorStore.add(chunks);
            log.info("RAG loaded: {} files -> {} chunks into Redis vector store",
                    resources.length, chunks.size());

        } catch (IOException e) {
            log.error("Failed to load RAG documents", e);
        }
    }
}
