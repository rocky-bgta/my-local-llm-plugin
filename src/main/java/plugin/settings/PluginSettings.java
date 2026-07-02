package plugin.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@State(name = "LocalLLMSettings", storages = @Storage("localLlm.xml"))
public class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public static class State {
        public String endpoint = "http://127.0.0.1:1234";
        public String model = "qwen2.5-coder-7b-instruct";
        public boolean includeFullContext = true;

        public String gitlabCliPath = "glab";
        public String gitlabProject = "";
        public String gitlabApiUrl = "https://gitlab.com";
        public String gitlabToken = "";

        public String jiraBaseUrl = "";
        public String jiraEmail = "";
        public String jiraToken = "";

        public String mcpServerUrl = "";
        public String mcpProtocolVersion = "2024-11-05";
        public String skillProfileId = UUID.randomUUID().toString();
    }

    private State state = new State();

    public static PluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public String getEndpoint()          { return state.endpoint; }
    public void   setEndpoint(String v)  { state.endpoint = v; }
    public String getModel()             { return state.model; }
    public void   setModel(String v)     { state.model = v; }
    public boolean isIncludeFullContext() { return state.includeFullContext; }
    public void setIncludeFullContext(boolean v) { state.includeFullContext = v; }

    public String getGitlabCliPath() { return state.gitlabCliPath; }
    public void setGitlabCliPath(String v) { state.gitlabCliPath = v; }
    public String getGitlabProject() { return state.gitlabProject; }
    public void setGitlabProject(String v) { state.gitlabProject = v; }
    public String getGitlabApiUrl() { return state.gitlabApiUrl; }
    public void setGitlabApiUrl(String v) { state.gitlabApiUrl = v; }
    public String getGitlabToken() { return state.gitlabToken; }
    public void setGitlabToken(String v) { state.gitlabToken = v; }

    public String getJiraBaseUrl() { return state.jiraBaseUrl; }
    public void setJiraBaseUrl(String v) { state.jiraBaseUrl = v; }
    public String getJiraEmail() { return state.jiraEmail; }
    public void setJiraEmail(String v) { state.jiraEmail = v; }
    public String getJiraToken() { return state.jiraToken; }
    public void setJiraToken(String v) { state.jiraToken = v; }

    public String getMcpServerUrl() { return state.mcpServerUrl; }
    public void setMcpServerUrl(String v) { state.mcpServerUrl = v; }
    public String getMcpProtocolVersion() { return state.mcpProtocolVersion; }
    public void setMcpProtocolVersion(String v) { state.mcpProtocolVersion = v; }

    public String getSkillProfileId() {
        if (state.skillProfileId == null || state.skillProfileId.isBlank()) {
            state.skillProfileId = UUID.randomUUID().toString();
        }
        return state.skillProfileId;
    }

    public void setSkillProfileId(String v) { state.skillProfileId = v; }
}
