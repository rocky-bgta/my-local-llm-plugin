package plugin.agent;

import plugin.rag.ContextCollector;
import plugin.rag.RetrievalResult;

import java.util.List;

public class RetrieverAgent {

    private static final int DEFAULT_TOP_K = 8;

    private final ContextCollector contextCollector;

    public RetrieverAgent(ContextCollector contextCollector) {
        this.contextCollector = contextCollector;
    }

    public List<RetrievalResult> retrieve(AgentContext ctx) {
        String query = buildQuery(ctx);
        String targetSymbol = ctx.getTask().targetSymbol();
        if (targetSymbol == null && ctx.getPlan() != null) {
            targetSymbol = new PlannerAgent().detectTargetSymbol(ctx.getTask().userMessage());
        }

        List<RetrievalResult> results = contextCollector.collect(query, targetSymbol, DEFAULT_TOP_K);

        ctx.setRetrievedContext(results);
        ctx.getWorkingMemory().setLastRetrievedContext(results);

        return results;
    }

    public void reindex() {
        contextCollector.reindex();
    }

    private String buildQuery(AgentContext ctx) {
        // Use PlannerAgent.expandQuery so BM25 benefits from task-specific terms
        String expanded = new PlannerAgent().expandQuery(ctx.getTask().userMessage());
        StringBuilder query = new StringBuilder(expanded);

        if (ctx.getPlan() != null) {
            ctx.getPlan().getAffectedFiles().forEach(f -> query.append(" ").append(f));
        }

        return query.toString();
    }
}
