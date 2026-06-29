package plugin.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.project.Project;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectMemory {

    private static final String MEMORY_FILE = ".local-llm/project-memory.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String basePath;
    private final Map<String, String> facts = new ConcurrentHashMap<>();

    public ProjectMemory(Project project) {
        this.basePath = project.getBasePath() != null ? project.getBasePath() : "";
        load();
    }

    public void remember(String key, String value) {
        facts.put(key, value);
        save();
    }

    public Optional<String> recall(String key) {
        return Optional.ofNullable(facts.get(key));
    }

    public void forget(String key) {
        facts.remove(key);
        save();
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(facts);
    }

    public String buildContextSummary() {
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# Project Memory\n");
        facts.forEach((k, v) -> sb.append("- **").append(k).append("**: ").append(v).append("\n"));
        return sb.toString();
    }

    private void load() {
        Path path = Paths.get(basePath, MEMORY_FILE);
        if (!Files.exists(path)) return;
        try (Reader r = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = GSON.fromJson(r, type);
            if (loaded != null) facts.putAll(loaded);
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            Path path = Paths.get(basePath, MEMORY_FILE);
            Files.createDirectories(path.getParent());
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
                GSON.toJson(facts, w);
            }
        } catch (IOException ignored) {}
    }
}
