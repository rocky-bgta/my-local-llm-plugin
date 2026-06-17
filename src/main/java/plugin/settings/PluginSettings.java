package plugin.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "LocalLLMSettings", storages = @Storage("localLlm.xml"))
public class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public static class State {
        public String endpoint = "http://127.0.0.1:1234";
        public String model    = "qwen2.5-coder-7b-instruct";
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
}
