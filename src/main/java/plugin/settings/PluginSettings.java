package plugin.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
    name = "LocalLLMPluginSettings",
    storages = @Storage("LocalLLMAssistant.xml")
)
public class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public enum Backend {
        OLLAMA, LM_STUDIO
    }

    public static class State {
        public Backend selectedBackend = Backend.OLLAMA;
        public String ollamaBaseUrl = "http://localhost:11434";
        public String lmStudioBaseUrl = "http://localhost:1234";
        public String selectedModel = "";
        public String systemPrompt = "You are a helpful coding assistant.";
        public boolean streamingEnabled = true;
        public int maxTokens = 4096;
        public double temperature = 0.7;
        public boolean autoApplyEdits = false;
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

    public Backend getSelectedBackend() {
        return state.selectedBackend;
    }

    public void setSelectedBackend(Backend backend) {
        state.selectedBackend = backend;
    }

    public String getOllamaBaseUrl() {
        return state.ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String url) {
        state.ollamaBaseUrl = url;
    }

    public String getLmStudioBaseUrl() {
        return state.lmStudioBaseUrl;
    }

    public void setLmStudioBaseUrl(String url) {
        state.lmStudioBaseUrl = url;
    }

    public String getSelectedModel() {
        return state.selectedModel;
    }

    public void setSelectedModel(String model) {
        state.selectedModel = model;
    }

    public String getSystemPrompt() {
        return state.systemPrompt;
    }

    public void setSystemPrompt(String prompt) {
        state.systemPrompt = prompt;
    }

    public boolean isStreamingEnabled() {
        return state.streamingEnabled;
    }

    public void setStreamingEnabled(boolean enabled) {
        state.streamingEnabled = enabled;
    }

    public int getMaxTokens() {
        return state.maxTokens;
    }

    public void setMaxTokens(int tokens) {
        state.maxTokens = tokens;
    }

    public double getTemperature() {
        return state.temperature;
    }

    public void setTemperature(double temperature) {
        state.temperature = temperature;
    }

    public boolean isAutoApplyEdits() {
        return state.autoApplyEdits;
    }

    public void setAutoApplyEdits(boolean autoApply) {
        state.autoApplyEdits = autoApply;
    }

    public String getActiveBaseUrl() {
        return state.selectedBackend == Backend.OLLAMA ? state.ollamaBaseUrl : state.lmStudioBaseUrl;
    }
}
