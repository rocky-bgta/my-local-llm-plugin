package plugin.agent;

import plugin.planning.ExecutionPlan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlannerAgent {

    private static final Pattern CLASS_MENTION = Pattern.compile(
            "\\b([A-Z][a-zA-Z0-9]+(?:Service|Client|Manager|Handler|Util|Controller|Repository|Factory|Builder|Test)?)\\b"
    );

    public ExecutionPlan plan(AgentContext ctx) {
        AgentTask task = ctx.getTask();
        String message = task.userMessage();

        AgentTask.TaskType type = detectTaskType(message);
        String targetSymbol = task.targetSymbol() != null ? task.targetSymbol() : detectTargetSymbol(message);
        String targetFile = task.targetFile() != null ? task.targetFile() : "";

        ExecutionPlan plan = new ExecutionPlan(message);

        switch (type) {
            case GENERATE_TESTS -> {
                plan.addStep("Find " + targetSymbol + " and its dependencies")
                    .addStep("Find existing test patterns")
                    .addStep("Read pom.xml for test framework")
                    .addStep("Generate comprehensive JUnit 5 tests")
                    .addStep("Run tests to verify")
                    .withTestStrategy("JUnit 5 + Mockito, AAA pattern");
            }
            case FIX_BUG -> {
                plan.addStep("Locate the failing code")
                    .addStep("Retrieve related context")
                    .addStep("Identify root cause")
                    .addStep("Apply minimal fix")
                    .addStep("Run build and tests");
            }
            case ADD_FEATURE -> {
                plan.addStep("Analyze existing architecture")
                    .addStep("Retrieve related components")
                    .addStep("Plan minimal changes")
                    .addStep("Implement feature")
                    .addStep("Generate tests")
                    .addStep("Validate build");
            }
            case REFACTOR -> {
                plan.addStep("Retrieve all affected files")
                    .addStep("Analyze current structure")
                    .addStep("Apply refactoring")
                    .addStep("Ensure no regressions");
            }
            case EXPLAIN_CODE -> {
                plan.addStep("Retrieve " + targetSymbol)
                    .addStep("Retrieve related dependencies")
                    .addStep("Build explanation");
            }
            default -> {
                plan.addStep("Retrieve relevant context")
                    .addStep("Generate response");
            }
        }

        if (!targetSymbol.isEmpty()) plan.addAffectedFile(targetSymbol + ".java");
        if (!targetFile.isEmpty()) plan.addAffectedFile(targetFile);

        ctx.setPlan(plan);
        ctx.getWorkingMemory().startTask(message, targetSymbol, targetFile);

        return plan;
    }

    public AgentTask.TaskType detectTaskType(String message) {
        String m = message.toLowerCase();
        if (m.contains("test") || m.contains("spec") || m.contains("junit")) return AgentTask.TaskType.GENERATE_TESTS;
        if (m.contains("fix") || m.contains("bug") || m.contains("error") || m.contains("fail")) return AgentTask.TaskType.FIX_BUG;
        if (m.contains("add") || m.contains("implement") || m.contains("create") || m.contains("feature")) return AgentTask.TaskType.ADD_FEATURE;
        if (m.contains("refactor") || m.contains("clean") || m.contains("rename")) return AgentTask.TaskType.REFACTOR;
        if (m.contains("explain") || m.contains("what") || m.contains("how") || m.contains("why")) return AgentTask.TaskType.EXPLAIN_CODE;
        if (m.contains("document") || m.contains("javadoc")) return AgentTask.TaskType.DOCUMENT;
        return AgentTask.TaskType.GENERAL;
    }

    public String detectTargetSymbol(String message) {
        Matcher m = CLASS_MENTION.matcher(message);
        if (m.find()) return m.group(1);
        return "";
    }
}
