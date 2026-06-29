package plugin.rag;

import com.intellij.openapi.project.Project;
import plugin.index.ProjectIndexer;
import plugin.index.SymbolIndex;

import java.util.ArrayList;
import java.util.List;

public class ContextCollector {

    private final Project project;
    private final ProjectIndexer projectIndexer;
    private final PSICollector psiCollector;
    private final EmbeddingSearch embeddingSearch;
    private final Reranker reranker;

    public ContextCollector(Project project) {
        this.project = project;
        SymbolIndex symbolIndex = new SymbolIndex();
        this.projectIndexer = new ProjectIndexer(project, symbolIndex);
        this.psiCollector = new PSICollector(project, symbolIndex);
        this.embeddingSearch = new EmbeddingSearch(symbolIndex);
        this.reranker = new Reranker();
        ensureIndexed();
    }

    public List<RetrievalResult> collect(String query, String targetClass, int topK) {
        ensureIndexed();

        List<RetrievalResult> candidates = new ArrayList<>();

        // Step 1: PSI-based collection (high-confidence, structure-aware)
        candidates.addAll(psiCollector.collectForQuery(query, targetClass));

        // Step 2: Config files always included
        candidates.addAll(psiCollector.collectConfigFiles());

        // Step 3: Embedding/TF-IDF semantic search
        candidates.addAll(embeddingSearch.search(query, topK * 2));

        // Step 4: Similar test patterns if task involves tests
        if (isTestRelated(query)) {
            candidates.addAll(embeddingSearch.searchSimilarTests(
                    targetClass != null ? targetClass : query, topK));
        }

        // Step 5: Rerank and deduplicate
        return reranker.rerank(candidates, query, topK);
    }

    public void reindex() {
        projectIndexer.reindex();
    }

    private void ensureIndexed() {
        if (!projectIndexer.isIndexed()) {
            projectIndexer.index();
        }
    }

    private boolean isTestRelated(String query) {
        String q = query.toLowerCase();
        return q.contains("test") || q.contains("spec") || q.contains("assert")
                || q.contains("mock") || q.contains("junit") || q.contains("verify");
    }
}
