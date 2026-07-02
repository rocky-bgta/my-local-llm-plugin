package plugin.agent;

public record AgentTask(
        String userMessage,
        TaskType type,
        String targetSymbol,
        String targetFile
) {
    public enum TaskType {
        GENERATE_TESTS,
        EXPLAIN_CODE,
        ADD_FEATURE,
        FIX_BUG,
        REFACTOR,
        REVIEW_COMMIT,
        DOCUMENT,
        ANALYZE,
        ENV_INFO,
        GENERAL
    }

    public static AgentTask general(String message) {
        return new AgentTask(message, TaskType.GENERAL, null, null);
    }

    public static AgentTask withTarget(String message, TaskType type, String symbol, String file) {
        return new AgentTask(message, type, symbol, file);
    }
}
