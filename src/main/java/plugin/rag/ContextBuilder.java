package plugin.rag;

import plugin.llm.model.ChatMessage;

import java.util.List;

public class ContextBuilder {

    private static final int MAX_CONTEXT_CHARS = 28_000;
    private static final int MAX_HISTORY_MESSAGES = 8;

    public String build(String userTask, List<RetrievalResult> retrieved,
                        List<ChatMessage> history, String corrections) {
        StringBuilder sb = new StringBuilder();

        // System context header
        sb.append("You are an expert software engineering assistant.\n");
        sb.append("Use the retrieved codebase context below to answer accurately.\n\n");

        // Learned corrections
        if (corrections != null && !corrections.isBlank()) {
            sb.append("# Past Corrections (follow these rules)\n");
            sb.append(corrections).append("\n\n");
        }

        // Retrieved context
        if (!retrieved.isEmpty()) {
            sb.append("# Retrieved Codebase Context\n");
            int budget = MAX_CONTEXT_CHARS;
            for (RetrievalResult r : retrieved) {
                if (budget <= 0) break;
                String block = formatBlock(r);
                if (block.length() > budget) {
                    block = block.substring(0, budget) + "\n// [truncated due to context limit]\n```\n\n";
                }
                sb.append(block);
                budget -= block.length();
            }
        }

        // Conversation history (trimmed)
        List<ChatMessage> recentHistory = trimHistory(history);
        if (!recentHistory.isEmpty()) {
            sb.append("# Recent Conversation\n");
            for (ChatMessage msg : recentHistory) {
                sb.append("[").append(msg.role().toUpperCase()).append("]: ")
                        .append(msg.content()).append("\n\n");
            }
        }

        // Current task
        sb.append("# Current Task\n").append(userTask).append("\n");

        return sb.toString();
    }

    public String buildMinimal(String userTask, List<ChatMessage> history) {
        return build(userTask, List.of(), history, null);
    }

    private String formatBlock(RetrievalResult r) {
        String lang = inferLanguage(r.relativeFilePath());
        return "## " + r.relativeFilePath() + " [" + r.symbolType() + "]\n" +
                "```" + lang + "\n" +
                r.content() + "\n" +
                "```\n\n";
    }

    private String inferLanguage(String path) {
        if (path == null) return "";
        if (path.endsWith(".java")) return "java";
        if (path.endsWith(".xml")) return "xml";
        if (path.endsWith(".md")) return "markdown";
        if (path.endsWith(".json")) return "json";
        if (path.endsWith(".yml") || path.endsWith(".yaml")) return "yaml";
        return "";
    }

    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        if (history.size() <= MAX_HISTORY_MESSAGES) return history;
        return history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size());
    }
}
