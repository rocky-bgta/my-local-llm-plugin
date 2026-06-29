package plugin.rag;

import plugin.llm.model.ChatMessage;

import java.util.List;

/**
 * Assembles the final RAG context block sent to the LLM.
 *
 * Budget is tuned for Qwen2.5-Coder-7B / Qwen2.5-VL-7B:
 *  - Effective "sweet-spot" context for 7B ≈ 6 000–10 000 chars
 *    (roughly 2 000–3 500 tokens), leaving headroom for the system
 *    prompt, chat history, and the model's own generation.
 *  - MAX_CONTEXT_CHARS = 10 000 keeps total prompt ≤ ~14 000 chars.
 *  - MAX_HISTORY_MESSAGES = 4 — 7B degrades noticeably beyond ~6 turns.
 */
public class ContextBuilder {

    private static final int MAX_CONTEXT_CHARS   = 10_000;
    private static final int MAX_HISTORY_MESSAGES = 4;

    public String build(String userTask, List<RetrievalResult> retrieved,
                        List<ChatMessage> history, String corrections) {
        StringBuilder sb = new StringBuilder();

        if (corrections != null && !corrections.isBlank()) {
            sb.append("# Past Corrections\n").append(corrections.strip()).append("\n\n");
        }

        if (!retrieved.isEmpty()) {
            sb.append("# Retrieved Context\n");
            int budget = MAX_CONTEXT_CHARS;
            for (RetrievalResult r : retrieved) {
                if (budget <= 0) break;
                String block = formatBlock(r);
                if (block.length() > budget) {
                    block = block.substring(0, budget) + "\n// [truncated]\n```\n\n";
                    sb.append(block);
                    break;
                }
                sb.append(block);
                budget -= block.length();
            }
        }

        List<ChatMessage> recent = trimHistory(history);
        if (!recent.isEmpty()) {
            sb.append("# Recent Conversation\n");
            for (ChatMessage msg : recent) {
                sb.append("[").append(msg.role().toUpperCase()).append("]: ")
                  .append(msg.content()).append("\n\n");
            }
        }

        sb.append("# Task\n").append(userTask).append("\n");
        return sb.toString();
    }

    public String buildMinimal(String userTask, List<ChatMessage> history) {
        return build(userTask, List.of(), history, null);
    }

    private String formatBlock(RetrievalResult r) {
        String lang = inferLanguage(r.relativeFilePath());
        return "## " + r.relativeFilePath() + " [" + r.symbolType() + "]\n"
                + "```" + lang + "\n"
                + r.content() + "\n"
                + "```\n\n";
    }

    private String inferLanguage(String path) {
        if (path == null)                               return "";
        if (path.endsWith(".java"))                     return "java";
        if (path.endsWith(".xml"))                      return "xml";
        if (path.endsWith(".md"))                       return "markdown";
        if (path.endsWith(".json"))                     return "json";
        if (path.endsWith(".yml") || path.endsWith(".yaml")) return "yaml";
        return "";
    }

    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        if (history.size() <= MAX_HISTORY_MESSAGES) return history;
        return history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size());
    }
}
