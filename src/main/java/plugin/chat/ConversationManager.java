package plugin.chat;

import com.intellij.openapi.project.Project;
import plugin.agent.AgentPipeline;
import plugin.memory.ConversationMemory;
import plugin.memory.ProjectMemory;

import java.util.HashMap;
import java.util.Map;

public class ConversationManager {

    private final Map<String, AgentPipeline> pipelines = new HashMap<>();

    public AgentPipeline getOrCreatePipeline(Project project) {
        String key = project.getBasePath() != null ? project.getBasePath() : project.getName();
        return pipelines.computeIfAbsent(key, k -> new AgentPipeline(project));
    }

    public void resetConversation(Project project) {
        AgentPipeline pipeline = getOrCreatePipeline(project);
        pipeline.getConversationMemory().reset();
    }

    public ConversationMemory getConversationMemory(Project project) {
        return getOrCreatePipeline(project).getConversationMemory();
    }

    public ProjectMemory getProjectMemory(Project project) {
        return getOrCreatePipeline(project).getProjectMemory();
    }

    public void reindex(Project project) {
        getOrCreatePipeline(project).reindex();
    }

    public void disposePipeline(Project project) {
        String key = project.getBasePath() != null ? project.getBasePath() : project.getName();
        pipelines.remove(key);
    }
}
