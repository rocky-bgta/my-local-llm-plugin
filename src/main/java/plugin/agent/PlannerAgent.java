package plugin.agent;

import plugin.planning.ExecutionPlan;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies task type, target symbol, and expands the query with
 * domain-relevant terms before retrieval.
 *
 * Query expansion is key for Qwen2.5-Coder-7B: the BM25 index scores
 * exact term matches, so expanding "generate tests for LocalLLMClient"
 * to include "streamChat fetchModels endpoint HttpClient" surfaces the
 * dependency files that give the model enough context to write correct tests.
 */
public class PlannerAgent {

    private static final Pattern CLASS_MENTION = Pattern.compile(
            "\\b([A-Z][a-zA-Z0-9]+(?:Service|Client|Manager|Handler|Util|Controller" +
            "|Repository|Factory|Builder|Test|Panel|Window|Action|Agent|Tool)?)\\b"
    );

    // Capitalised words that match CLASS_MENTION but are command verbs or filler,
    // not the class under discussion. Without this, "Generate tests for Foo"
    // resolves the target to "Generate" — so the real class source never gets
    // retrieved as PSI_PRIMARY and the model writes tests from guesswork.
    private static final Set<String> COMMAND_WORDS = Set.of(
            "Generate", "Write", "Create", "Add", "Implement", "Fix", "Make", "Build",
            "Test", "Tests", "Please", "Update", "Refactor", "Explain", "Document",
            "Run", "Use", "The", "This", "That", "For", "And", "With", "Class",
            "Method", "Code", "File", "JUnit", "Mockito", "Java", "New"
    );

    // Task-type → expansion terms added to the BM25 query
    private static final Map<AgentTask.TaskType, String> EXPANSION = Map.of(
            AgentTask.TaskType.GENERATE_TESTS, "test junit mockito assert verify beforeEach",
            AgentTask.TaskType.FIX_BUG,        "error exception stacktrace fix compile",
            AgentTask.TaskType.ADD_FEATURE,     "implement method interface service",
            AgentTask.TaskType.REFACTOR,        "rename extract inline restructure",
            AgentTask.TaskType.EXPLAIN_CODE,    "class method field dependency",
            AgentTask.TaskType.DOCUMENT,        "javadoc param return throws",
            AgentTask.TaskType.ENV_INFO,        "environment os java maven shell platform system"
    );

    public ExecutionPlan plan(AgentContext ctx) {
        AgentTask task    = ctx.getTask();
        String message    = task.userMessage();
        AgentTask.TaskType type   = detectTaskType(message);
        String targetSymbol = task.targetSymbol() != null ? task.targetSymbol() : detectTargetSymbol(message);
        String targetFile   = task.targetFile()   != null ? task.targetFile()   : "";

        ExecutionPlan plan = new ExecutionPlan(message);

        switch (type) {
            case GENERATE_TESTS -> plan
                    .addStep("PSI: locate " + targetSymbol + " and direct dependencies")
                    .addStep("BM25: find existing test patterns and JUnit 5 examples")
                    .addStep("Read pom.xml for test framework versions")
                    .addStep("Rerank top-6 files for 7B context budget")
                    .addStep("LLM: generate JUnit 5 + Mockito tests")
                    .addStep("Apply CREATE_FILE, then run <RUN_TESTS test=\"" + targetSymbol + "Test\">")
                    .withTestStrategy("JUnit 5 + Mockito · AAA pattern · edge cases + happy path");
            case FIX_BUG -> plan
                    .addStep("PSI: locate failing class")
                    .addStep("BM25: find related error-handling patterns")
                    .addStep("LLM: identify root cause and apply MODIFY_FILE fix")
                    .addStep("Run <CHECK_COMPILATION /> then <RUN_TESTS />");
            case ADD_FEATURE -> plan
                    .addStep("PSI: analyse integration points in existing architecture")
                    .addStep("BM25: retrieve related components")
                    .addStep("LLM: design minimal implementation")
                    .addStep("Apply file operations, generate tests, run build");
            case REFACTOR -> plan
                    .addStep("PSI: find all usages of target")
                    .addStep("LLM: apply refactoring across affected files")
                    .addStep("Run <CHECK_COMPILATION />");
            case EXPLAIN_CODE -> plan
                    .addStep("PSI: retrieve " + (targetSymbol.isEmpty() ? "relevant code" : targetSymbol))
                    .addStep("BM25: collect dependency context")
                    .addStep("LLM: explain");
            default -> plan
                    .addStep("BM25: find relevant context")
                    .addStep("LLM: respond");
        }

        if (!targetSymbol.isEmpty()) plan.addAffectedFile(targetSymbol + ".java");
        if (!targetFile.isEmpty())   plan.addAffectedFile(targetFile);

        ctx.setPlan(plan);
        ctx.getWorkingMemory().startTask(message, targetSymbol, targetFile);
        return plan;
    }

    /**
     * Returns the original query augmented with task-specific expansion terms
     * and the detected target symbol.  Used by RetrieverAgent as the BM25 query.
     */
    public String expandQuery(String query) {
        AgentTask.TaskType type = detectTaskType(query);
        String target = detectTargetSymbol(query);
        StringBuilder expanded = new StringBuilder(query);
        String extra = EXPANSION.get(type);
        if (extra != null) expanded.append(' ').append(extra);
        if (!target.isEmpty()) expanded.append(' ').append(target);
        return expanded.toString();
    }

    public AgentTask.TaskType detectTaskType(String message) {
        String m = message.toLowerCase();
        if (m.contains("env info") || m.contains("environment info") || m.contains("my environment")
                || m.contains("system info") || m.contains("platform info") || m.contains("my setup")
                || (m.contains("give") && m.contains("env")) || (m.contains("send") && m.contains("env"))
                || (m.contains("show") && m.contains("env")))
            return AgentTask.TaskType.ENV_INFO;
        if (m.contains("test") || m.contains("spec") || m.contains("junit"))
            return AgentTask.TaskType.GENERATE_TESTS;
        if (m.contains("fix") || m.contains("bug") || m.contains("error") || m.contains("fail"))
            return AgentTask.TaskType.FIX_BUG;
        if (m.contains("add") || m.contains("implement") || m.contains("create") || m.contains("feature"))
            return AgentTask.TaskType.ADD_FEATURE;
        if (m.contains("refactor") || m.contains("clean") || m.contains("rename"))
            return AgentTask.TaskType.REFACTOR;
        if (m.contains("explain") || m.contains("what") || m.contains("how") || m.contains("why"))
            return AgentTask.TaskType.EXPLAIN_CODE;
        if (m.contains("document") || m.contains("javadoc"))
            return AgentTask.TaskType.DOCUMENT;
        return AgentTask.TaskType.GENERAL;
    }

    public String detectTargetSymbol(String message) {
        Matcher m = CLASS_MENTION.matcher(message);
        String firstNonCommand = "";
        while (m.find()) {
            String candidate = m.group(1);
            if (COMMAND_WORDS.contains(candidate)) continue;
            // An internal capital (LocalLLMClient, ChatPanel) is a strong signal of a
            // real type name — prefer it over the first plain capitalised word.
            if (hasInternalUpperCase(candidate)) return candidate;
            if (firstNonCommand.isEmpty()) firstNonCommand = candidate;
        }
        return firstNonCommand;
    }

    private static boolean hasInternalUpperCase(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) return true;
        }
        return false;
    }
}
