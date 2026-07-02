package plugin.planning;

import plugin.agent.AgentTask;

public class Planner {

    public ExecutionPlan createPlan(String userRequest, AgentTask.TaskType type, String targetSymbol) {
        ExecutionPlan plan = new ExecutionPlan(userRequest);

        switch (type) {
            case GENERATE_TESTS -> buildTestPlan(plan, targetSymbol);
            case FIX_BUG -> buildBugFixPlan(plan, targetSymbol);
            case ADD_FEATURE -> buildFeaturePlan(plan, targetSymbol);
            case REFACTOR -> buildRefactorPlan(plan, targetSymbol);
            case EXPLAIN_CODE -> buildExplainPlan(plan, targetSymbol);
            case DOCUMENT -> buildDocPlan(plan, targetSymbol);
            default -> buildGeneralPlan(plan);
        }

        return plan;
    }

    private void buildTestPlan(ExecutionPlan plan, String target) {
        plan.addStep("PSI: locate " + target)
            .addStep("PSI: find related classes via dependency analysis")
            .addStep("Embedding: find similar test patterns")
            .addStep("Read project config for test framework detection")
            .addStep("Rerank: select top 8 context files")
            .addStep("LLM: generate tests in the project's native framework")
            .addStep("Editor: apply CREATE_FILE for test class")
            .addStep("Maven: run tests to verify")
            .withTestStrategy("Project-native framework, edge cases + happy path");
        if (target != null) {
            plan.addAffectedFile(target + "Test");
        }
    }

    private void buildBugFixPlan(ExecutionPlan plan, String target) {
        plan.addStep("PSI: locate failing class")
            .addStep("Embedding: find similar error handling patterns")
            .addStep("LLM: identify root cause and fix")
            .addStep("Editor: apply MODIFY_FILE")
            .addStep("Maven: compile to verify fix")
            .addStep("Maven: run tests")
            .addRisk("Fix may introduce regressions in callers");
    }

    private void buildFeaturePlan(ExecutionPlan plan, String target) {
        plan.addStep("PSI: analyze existing architecture")
            .addStep("PSI: find integration points")
            .addStep("LLM: design minimal implementation")
            .addStep("Editor: apply file operations")
            .addStep("Test: generate tests for new feature")
            .addStep("Maven: full build")
            .addRisk("New feature may conflict with existing plugin.xml registrations");
    }

    private void buildRefactorPlan(ExecutionPlan plan, String target) {
        plan.addStep("PSI: find all usages of target")
            .addStep("LLM: propose refactoring")
            .addStep("Editor: apply changes to all affected files")
            .addStep("Maven: compile to verify")
            .addRisk("Refactoring across multiple files risks compilation errors");
    }

    private void buildExplainPlan(ExecutionPlan plan, String target) {
        plan.addStep("PSI: retrieve " + (target != null ? target : "relevant code"))
            .addStep("PSI: collect dependencies for full context")
            .addStep("LLM: explain clearly");
    }

    private void buildDocPlan(ExecutionPlan plan, String target) {
        plan.addStep("PSI: retrieve target class methods")
            .addStep("LLM: generate Javadoc")
            .addStep("Editor: apply MODIFY_FILE with documentation");
    }

    private void buildGeneralPlan(ExecutionPlan plan) {
        plan.addStep("Embedding: find relevant context")
            .addStep("LLM: respond to query");
    }
}
