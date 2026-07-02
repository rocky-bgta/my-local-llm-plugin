package plugin.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlannerAgentTest {

    @Test
    void expandsChatPanelTestRequestsTowardHelperLogic() {
        PlannerAgent planner = new PlannerAgent();

        String expanded = planner.expandQuery("write unit test for ChatPanel");

        assertTrue(expanded.contains("ChatPanel"));
        assertTrue(expanded.contains("ChatPanelSupport"));
    }

    @Test
    void detectsCommitReviewRequests() {
        PlannerAgent planner = new PlannerAgent();

        assertEquals(AgentTask.TaskType.REVIEW_COMMIT,
                planner.detectTaskType("review this last git commit with the jira ticket"));
    }

    @Test
    void expandsDockerAndHelmRequestsTowardContainerTerms() {
        PlannerAgent planner = new PlannerAgent();

        String expanded = planner.expandQuery("build the Dockerfile and Helm chart");

        assertTrue(expanded.contains("Dockerfile"));
        assertTrue(expanded.contains("helm"));
        assertTrue(expanded.contains("container"));
    }

    @Test
    void detectsReadmeRequestsAsDocumentation() {
        PlannerAgent planner = new PlannerAgent();

        assertEquals(AgentTask.TaskType.DOCUMENT,
                planner.detectTaskType("create a README for this project"));
    }

    @Test
    void detectsDeleteAndCleanupRequestsAsRefactor() {
        PlannerAgent planner = new PlannerAgent();

        assertEquals(AgentTask.TaskType.REFACTOR,
                planner.detectTaskType("delete unnecessary files and cleanup dead code"));
        assertTrue(planner.expandQuery("cleanup dead code").contains("cleanup"));
    }
}
