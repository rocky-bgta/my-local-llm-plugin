package plugin.memory;

import plugin.settings.PluginSettings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public final class InternalWorkspaceStore {

    private static final String ROOT_DIR = ".local-llm";
    private static final String INSTANCES_DIR = "instances";
    private static final String MEMORY_DIR = "memory";
    private static final String SKILLS_DIR = "skills";
    private static final String CORRECTIONS_DIR = "corrections";
    private static final String ATTACHMENTS_DIR = "attachments";
    private static final String SESSIONS_DIR = "sessions";
    private static final String FALLBACK_INSTANCE_ID = UUID.randomUUID().toString();

    private InternalWorkspaceStore() {}

    public static Path root(String basePath) {
        return Paths.get(basePath == null ? "" : basePath, ROOT_DIR, INSTANCES_DIR, instanceId());
    }

    public static Path legacyRoot(String basePath) {
        return Paths.get(basePath == null ? "" : basePath, ROOT_DIR);
    }

    public static String instanceId() {
        String override = System.getProperty("local.llm.instance.id");
        if (override != null && !override.isBlank()) {
            return sanitize(override);
        }
        try {
            String profileId = PluginSettings.getInstance().getSkillProfileId();
            if (profileId != null && !profileId.isBlank()) {
                return sanitize(profileId);
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_INSTANCE_ID;
    }

    public static Path memoryDir(String basePath) {
        return root(basePath).resolve(MEMORY_DIR);
    }

    public static Path skillsDir(String basePath) {
        return root(basePath).resolve(SKILLS_DIR);
    }

    public static Path correctionsDir(String basePath) {
        return root(basePath).resolve(CORRECTIONS_DIR);
    }

    public static Path attachmentsDir(String basePath) {
        return root(basePath).resolve(ATTACHMENTS_DIR);
    }

    public static Path sessionsDir(String basePath) {
        return root(basePath).resolve(SESSIONS_DIR);
    }

    public static Path projectMemoryFile(String basePath) {
        return memoryDir(basePath).resolve("project-memory.json");
    }

    public static Path skillMemoryFile(String basePath) {
        return skillsDir(basePath).resolve("skills.json");
    }

    public static Path correctionsFile(String basePath) {
        return correctionsDir(basePath).resolve("llm-corrections.md");
    }

    public static Path conversationSessionFile(String basePath, String sessionId) {
        return sessionsDir(basePath).resolve((sessionId == null || sessionId.isBlank() ? "session" : sessionId) + ".json");
    }

    private static String sanitize(String value) {
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
