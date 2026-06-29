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
        StringBuilder query = new StringBuilder(ctx.getTask().userMessage());

        String target = ctx.getTask().targetSymbol();
        if (target != null && !target.isBlank()) query.append(" ").append(target);

        if (ctx.getPlan() != null) {
            ctx.getPlan().getAffectedFiles().forEach(f -> query.append(" ").append(f));
        }

        return query.toString();
    }
}
