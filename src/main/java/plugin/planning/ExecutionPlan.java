package plugin.planning;

import java.util.ArrayList;
import java.util.List;

public class ExecutionPlan {

    public enum PlanStage {
        RETRIEVE_CONTEXT,
        BUILD_PROMPT,
        CALL_LLM,
        APPLY_EDITS,
        RUN_TESTS,
        VALIDATE,
        DONE
    }

    private final String goal;
    private final List<String> steps = new ArrayList<>();
    private final List<String> affectedFiles = new ArrayList<>();
    private final List<String> risks = new ArrayList<>();
    private PlanStage currentStage = PlanStage.RETRIEVE_CONTEXT;
    private String testStrategy = "";

    public ExecutionPlan(String goal) {
        this.goal = goal;
    }

    public ExecutionPlan addStep(String step) {
        steps.add(step);
        return this;
    }

    public ExecutionPlan addAffectedFile(String file) {
        affectedFiles.add(file);
        return this;
    }

    public ExecutionPlan addRisk(String risk) {
        risks.add(risk);
        return this;
    }

    public ExecutionPlan withTestStrategy(String strategy) {
        this.testStrategy = strategy;
        return this;
    }

    public void advance() {
        PlanStage[] stages = PlanStage.values();
        int next = currentStage.ordinal() + 1;
        if (next < stages.length) {
            currentStage = stages[next];
        }
    }

    public String getGoal() { return goal; }
    public List<String> getSteps() { return List.copyOf(steps); }
    public List<String> getAffectedFiles() { return List.copyOf(affectedFiles); }
    public List<String> getRisks() { return List.copyOf(risks); }
    public PlanStage getCurrentStage() { return currentStage; }
    public String getTestStrategy() { return testStrategy; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal).append("\n");
        steps.forEach(s -> sb.append("  - ").append(s).append("\n"));
        if (!affectedFiles.isEmpty()) {
            sb.append("Files: ").append(String.join(", ", affectedFiles)).append("\n");
        }
        if (!testStrategy.isBlank()) {
            sb.append("Tests: ").append(testStrategy).append("\n");
        }
        return sb.toString();
    }
}
