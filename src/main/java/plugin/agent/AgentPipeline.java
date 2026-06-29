package plugin.agent;

import com.intellij.openapi.project.Project;
import plugin.memory.ConversationMemory;
import plugin.memory.ProjectMemory;
import plugin.memory.WorkingMemory;
import plugin.rag.ContextCollector;
import plugin.util.FileOperationUtil;

import java.util.function.Consumer;

/**
 * Orchestrates the full agent pipeline:
 * Planner → Retriever → LLMAgent → EditorAgent → TestAgent → ValidationAgent
 */
public class AgentPipeline {

    private final Project project;
    private final PlannerAgent planner;
    private final RetrieverAgent retriever;
    private final LLMAgent llmAgent;
    private final EditorAgent editorAgent;
    private final TestAgent testAgent;
    private final ValidationAgent validationAgent;
    private final ConversationMemory conversationMemory;
    private final ProjectMemory projectMemory;
    private final WorkingMemory workingMemory;

    public AgentPipeline(Project project) {
        this.project = project;
        this.planner = new PlannerAgent();
        ContextCollector collector = new ContextCollector(project);
        this.retriever = new RetrieverAgent(collector);
        this.llmAgent = new LLMAgent();
        this.editorAgent = new EditorAgent();
        this.testAgent = new TestAgent();
        this.validationAgent = new ValidationAgent();
        this.conversationMemory = new ConversationMemory();
        this.projectMemory = new ProjectMemory(project);
        this.workingMemory = new WorkingMemory();
    }

    public void execute(String userMessage, Consumer<String> onToken,
                        Consumer<FileOperationUtil.FileOpResult> onEditsApplied) throws Exception {
        conversationMemory.addUserMessage(userMessage);

        AgentTask task = AgentTask.general(userMessage);
        AgentContext ctx = new AgentContext(project, task, conversationMemory,
                projectMemory, workingMemory);

        // Step 1: Plan
        planner.plan(ctx);

        // Step 2: Retrieve context (RAG)
        retriever.retrieve(ctx);

        // Step 3: Stream LLM response
        llmAgent.stream(ctx, onToken);

        // Step 4: Apply file edits
        FileOperationUtil.FileOpResult edits = editorAgent.apply(ctx);
        if (onEditsApplied != null) onEditsApplied.accept(edits);

        // Step 5: Run tests if requested by LLM response
        if (edits.runTests) {
            testAgent.runTests(ctx, edits.testName);
            ctx.setTestsRan(true);
        }

        // Step 6: Validate
        validationAgent.validate(ctx);

        // Step 7: Update project memory with learned patterns
        updateProjectMemory(ctx);
    }

    public void reindex() {
        retriever.reindex();
    }

    public ConversationMemory getConversationMemory() { return conversationMemory; }
    public ProjectMemory getProjectMemory() { return projectMemory; }
    public WorkingMemory getWorkingMemory() { return workingMemory; }

    private void updateProjectMemory(AgentContext ctx) {
        if (!ctx.getWorkingMemory().getRecentlyModifiedFiles().isEmpty()) {
            projectMemory.remember("last_modified_files",
                    String.join(", ", ctx.getWorkingMemory().getRecentlyModifiedFiles()));
        }
    }
}
