package plugin.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SkillMemory {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String basePath;
    private final Map<String, SkillEntry> skills = new ConcurrentHashMap<>();

    public SkillMemory(Project project) {
        this.basePath = project.getBasePath() != null ? project.getBasePath() : "";
        load();
    }

    public synchronized void remember(String name, String description) {
        if (name == null || name.isBlank() || description == null || description.isBlank()) return;
        String key = normalize(name);
        SkillEntry existing = skills.get(key);
        String createdAt = existing == null || existing.createdAt() == null ? LocalDate.now().toString() : existing.createdAt();
        String updatedAt = LocalDate.now().toString();
        boolean enabled = existing == null || existing.enabled();
        skills.put(key, new SkillEntry(name.trim(), description.trim(), createdAt, updatedAt, enabled));
        save();
    }

    public synchronized void rememberFromSession(String name, String userRequest, String assistantSolution) {
        if (assistantSolution == null || assistantSolution.isBlank()) return;
        String normalizedName = (name == null || name.isBlank()) ? "general-skill" : name.trim();
        StringBuilder description = new StringBuilder();
        if (userRequest != null && !userRequest.isBlank()) {
            description.append("User request: ").append(userRequest.trim()).append("\n");
        }
        description.append("Solution pattern: ").append(summarizeSolution(assistantSolution.trim()));
        remember(normalizedName, description.toString());
    }

    public synchronized void update(String originalName, String newName, String description) {
        SkillEntry existing = skills.get(normalize(originalName));
        boolean enabled = existing == null || existing.enabled();
        update(originalName, newName, description, enabled);
    }

    public synchronized void update(String originalName, String newName, String description, boolean enabled) {
        if (originalName == null || originalName.isBlank() || newName == null || newName.isBlank()
                || description == null || description.isBlank()) {
            return;
        }
        String originalKey = normalize(originalName);
        SkillEntry existing = skills.remove(originalKey);
        String createdAt = existing == null || existing.createdAt() == null ? LocalDate.now().toString() : existing.createdAt();
        skills.put(normalize(newName), new SkillEntry(newName.trim(), description.trim(), createdAt, LocalDate.now().toString(), enabled));
        save();
    }

    public synchronized void setEnabled(String name, boolean enabled) {
        if (name == null || name.isBlank()) return;
        String key = normalize(name);
        SkillEntry existing = skills.get(key);
        if (existing == null) return;
        skills.put(key, new SkillEntry(existing.name(), existing.description(), existing.createdAt(), LocalDate.now().toString(), enabled));
        save();
    }

    public synchronized void delete(String name) {
        if (name == null || name.isBlank()) return;
        skills.remove(normalize(name));
        save();
    }

    public synchronized Optional<SkillEntry> recall(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(skills.get(normalize(name)));
    }

    public synchronized List<SkillEntry> findRelevant(String query, int limit) {
        if (skills.isEmpty()) return List.of();
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<SkillEntry> matches = new ArrayList<>(skills.values().stream().filter(SkillEntry::enabled).collect(Collectors.toList()));
        matches.sort((a, b) -> score(b, lower) - score(a, lower));
        if (limit > 0 && matches.size() > limit) {
            return matches.subList(0, limit);
        }
        return matches;
    }

    public synchronized List<SkillEntry> listAll() {
        List<SkillEntry> entries = new ArrayList<>(skills.values());
        entries.sort((a, b) -> {
            int updatedCompare = compareNullable(b.updatedAt(), a.updatedAt());
            if (updatedCompare != 0) return updatedCompare;
            return a.name().compareToIgnoreCase(b.name());
        });
        return entries;
    }

    public synchronized String buildContextSummary(String query) {
        List<SkillEntry> relevant = findRelevant(query, 5);
        if (relevant.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# Skills / Memory\n");
        for (SkillEntry skill : relevant) {
            sb.append("- **").append(skill.name()).append("**")
              .append(" (").append(skill.enabled() ? "enabled" : "disabled").append(", updated ").append(skill.updatedAt()).append("): ")
              .append(skill.description()).append("\n");
        }
        return sb.toString();
    }

    public synchronized Map<String, SkillEntry> all() {
        return Collections.unmodifiableMap(skills);
    }

    private int score(SkillEntry entry, String query) {
        String content = (entry.name() + " " + entry.description()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : query.split("\\W+")) {
            if (token.isBlank()) continue;
            if (content.contains(token)) score++;
        }
        return score;
    }

    private void load() {
        Path path = InternalWorkspaceStore.skillMemoryFile(basePath);
        Path legacyPath = InternalWorkspaceStore.legacyRoot(basePath).resolve("skills.json");
        Path sourcePath = Files.exists(path) ? path : legacyPath;
        if (!Files.exists(sourcePath)) return;
        try (Reader reader = Files.newBufferedReader(sourcePath, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, SkillEntry>>() {}.getType();
            Map<String, SkillEntry> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                for (Map.Entry<String, SkillEntry> entry : loaded.entrySet()) {
                    SkillEntry normalizedEntry = normalizeEntry(entry.getValue());
                    if (normalizedEntry != null) {
                        skills.put(normalize(normalizedEntry.name()), normalizedEntry);
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            Path path = InternalWorkspaceStore.skillMemoryFile(basePath);
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(skills, writer);
            }
        } catch (IOException ignored) {}
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String summarizeSolution(String text) {
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 1800) {
            return collapsed;
        }
        return collapsed.substring(0, 1800) + " [...truncated]";
    }

    private static int compareNullable(String left, String right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private static SkillEntry normalizeEntry(SkillEntry entry) {
        if (entry == null || entry.name() == null || entry.name().isBlank()) return null;
        String createdAt = entry.createdAt() == null || entry.createdAt().isBlank() ? LocalDate.now().toString() : entry.createdAt();
        String updatedAt = entry.updatedAt() == null || entry.updatedAt().isBlank() ? createdAt : entry.updatedAt();
        return new SkillEntry(entry.name().trim(), entry.description() == null ? "" : entry.description().trim(), createdAt, updatedAt, entry.enabled());
    }

    public record SkillEntry(String name, String description, String createdAt, String updatedAt, boolean enabled) {}
}
