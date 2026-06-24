package plugin;

import org.junit.jupiter.api.Test;
import plugin.settings.PluginSettings;

import static org.junit.jupiter.api.Assertions.*;

public class PluginSettingsTest {

    @Test
    void defaultEndpointIsSet() {
        PluginSettings settings = new PluginSettings();
        assertEquals("http://127.0.0.1:1234", settings.getEndpoint());
    }

    @Test
    void defaultModelIsSet() {
        PluginSettings settings = new PluginSettings();
        assertEquals("qwen2.5-coder-7b-instruct", settings.getModel());
    }

    @Test
    void defaultIncludeFullContextIsTrue() {
        PluginSettings settings = new PluginSettings();
        assertTrue(settings.isIncludeFullContext());
    }

    @Test
    void setEndpointUpdatesValue() {
        PluginSettings settings = new PluginSettings();
        settings.setEndpoint("http://localhost:5678");
        assertEquals("http://localhost:5678", settings.getEndpoint());
    }

    @Test
    void setModelUpdatesValue() {
        PluginSettings settings = new PluginSettings();
        settings.setModel("llama3");
        assertEquals("llama3", settings.getModel());
    }

    @Test
    void setIncludeFullContextUpdatesValue() {
        PluginSettings settings = new PluginSettings();
        settings.setIncludeFullContext(false);
        assertFalse(settings.isIncludeFullContext());
    }

    @Test
    void loadStateReplacesCurrentState() {
        PluginSettings settings = new PluginSettings();
        PluginSettings.State newState = new PluginSettings.State();
        newState.endpoint = "http://remote:9999";
        newState.model = "gpt-4";
        newState.includeFullContext = false;
        settings.loadState(newState);
        assertEquals("http://remote:9999", settings.getEndpoint());
        assertEquals("gpt-4", settings.getModel());
        assertFalse(settings.isIncludeFullContext());
    }

    @Test
    void getStateReturnsNonNull() {
        PluginSettings settings = new PluginSettings();
        assertNotNull(settings.getState());
    }
}
