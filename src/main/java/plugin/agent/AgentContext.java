package plugin.agent;

import com.intellij.openapi.project.Project;
import plugin.llm.model.ChatMessage;
import plugin.memory.ConversationMemory;
import plugin.memory.ProjectMemory;
import plugin.memory.WorkingMemory;
import plugin.planning.ExecutionPlan;
import plugin.rag.RetrievalResult;

import java.util.ArrayList;
import java.util.List;

public class AgentContext {

    private final Project project;
    private final AgentTask task;
    private final ConversationMemory conversationMemory;
    private final ProjectMemory projectMemory;
    private final WorkingMemory workingMemory;

    private ExecutionPlan plan;
    private List<RetrievalResult> retrievedContext = new ArrayList<>();
    private String builtPrompt = "";
    private String llmResponse = "";
    private boolean editApplied = false;
    private boolean testsRan = false;
    private boolean validationPassed = false;

    public AgentContext(Project project, AgentTask task,
                        ConversationMemory conversationMemory,
                        ProjectMemory projectMemory,
                        WorkingMemory workingMemory) {
        this.project = project;
        this.task = task;
        this.conversationMemory = conversationMemory;
        this.projectMemory = projectMemory;
        this.workingMemory = workingMemory;
    }

    public Project getProject() { return project; }
    public AgentTask getTask() { return task; }
    public ConversationMemory getConversationMemory() { return conversationMemory; }
    public ProjectMemory getProjectMemory() { return projectMemory; }
    public WorkingMemory getWorkingMemory() { return workingMemory; }

    public ExecutionPlan getPlan() { return plan; }
    public void setPlan(ExecutionPlan plan) { this.plan = plan; }

    public List<RetrievalResult> getRetrievedContext() { return List.copyOf(retrievedContext); }
    public void setRetrievedContext(List<RetrievalResult> ctx) { this.retrievedContext = new ArrayList<>(ctx); }

    public String getBuiltPrompt() { return builtPrompt; }
    public void setBuiltPrompt(String prompt) { this.builtPrompt = prompt; }

    public String getLlmResponse() { return llmResponse; }
    public void setLlmResponse(String response) { this.llmResponse = response; }

    public boolean isEditApplied() { return editApplied; }
    public void setEditApplied(boolean v) { this.editApplied = v; }

    public boolean isTestsRan() { return testsRan; }
    public void setTestsRan(boolean v) { this.testsRan = v; }

    public boolean isValidationPassed() { return validationPassed; }
    public void setValidationPassed(boolean v) { this.validationPassed = v; }

    public List<ChatMessage> getHistory() { return conversationMemory.getHistory(); }
}
