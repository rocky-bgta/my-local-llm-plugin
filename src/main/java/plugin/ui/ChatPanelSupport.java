package plugin.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import plugin.llm.model.ChatMessage;
import plugin.llm.AttachmentData;
import plugin.psi.SourceFileScanner;
import plugin.util.EnvironmentInfoCollector;
import plugin.util.ProjectContextUtil;
import plugin.util.ContainerProjectUtil;
import plugin.util.LanguageSupportUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ChatPanelSupport {

    private static final String FILE_EXT_REGEX = "(?:java|kt|kts|go|py|js|jsx|ts|tsx|rs|php|rb|cs|scala|xml|yml|yaml|json|toml|gradle|lock|md|txt)";
    private static final Pattern BROKEN_FILE_PATTERN = Pattern.compile("[^\\s]+\\." + FILE_EXT_REGEX + "(?::\\[?\\d[^\\s]*)?");
    private static final Pattern GENERIC_FILE_REF_PATTERN = Pattern.compile("([^\\s]+\\." + FILE_EXT_REGEX + ")");
    private static final int DEFAULT_CONTEXT_BUDGET_TOKENS = 12_000;

    private ChatPanelSupport() {}

    record PromptHistoryState(String displayedText, int index, String draft) {}

    static List<String> extractBrokenFilePaths(String errorOutput) {
        String normalised = errorOutput == null ? "" : errorOutput.replace("\\", "/");
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = BROKEN_FILE_PATTERN.matcher(normalised);
        while (matcher.find()) {
            String hit = matcher.group(0).replaceAll(":\\[?\\d.*", "");
            paths.add(normalizeRelativePath(hit));
        }
        return new ArrayList<>(paths);
    }

    static boolean isSimpleSyntaxError(String errorOutput) {
        String lower = errorOutput == null ? "" : errorOutput.toLowerCase();
        return lower.contains("reached end of file") ||
               lower.contains("';' expected") ||
               lower.contains("'(' expected") ||
               lower.contains("')' expected") ||
               lower.contains("'{' expected") ||
               lower.contains("'}' expected") ||
               lower.contains("illegal start of expression") ||
               lower.contains("class, interface, or enum expected") ||
               lower.contains("not a statement");
    }

    static String scanProjectForErrorContext(Project project,
                                             String errorOutput,
                                             List<String> knownBrokenPaths) {
        String basePath = project == null ? null : project.getBasePath();
        if (basePath == null) return "";

        String normalised = errorOutput == null ? "" : errorOutput.replace("\\", "/");
        Set<String> referencedFiles = new LinkedHashSet<>();
        Matcher fileMatcher = GENERIC_FILE_REF_PATTERN.matcher(normalised);
        while (fileMatcher.find()) {
            referencedFiles.add(normalizeRelativePath(fileMatcher.group(1)));
        }

        Set<String> candidateNames = new LinkedHashSet<>();
        Matcher symbolMatcher = Pattern.compile("symbol:\\s+class\\s+(\\w+)").matcher(normalised);
        while (symbolMatcher.find()) candidateNames.add(symbolMatcher.group(1));

        Matcher locationMatcher = Pattern.compile("location:.*?(\\w+)$", Pattern.MULTILINE).matcher(normalised);
        while (locationMatcher.find()) candidateNames.add(locationMatcher.group(1));

        Matcher fileInErrorMatcher = Pattern.compile("/(\\w+?)(?:Test)?\\." + FILE_EXT_REGEX + ":\\[?\\d").matcher(normalised);
        while (fileInErrorMatcher.find()) candidateNames.add(fileInErrorMatcher.group(1));

        StringBuilder context = new StringBuilder();
        List<VirtualFile> sourceFiles = SourceFileScanner.scanAllSourceFiles(project);
        List<VirtualFile> configFiles = SourceFileScanner.scanConfigFiles(project);

        if (!referencedFiles.isEmpty()) {
            for (String relPath : referencedFiles) {
                appendFileContentIfExists(basePath, relPath, context);
            }
        }

        if (!candidateNames.isEmpty()) {
            for (VirtualFile vf : sourceFiles) {
                String name = vf.getName();
                String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                if (candidateNames.contains(name) || candidateNames.contains(stem)) {
                    appendFileContentIfExists(basePath, relativePath(basePath, vf.getPath()), context);
                }
            }
            for (VirtualFile vf : configFiles) {
                if (candidateNames.contains(vf.getName())) {
                    appendFileContentIfExists(basePath, relativePath(basePath, vf.getPath()), context);
                }
            }
        }

        Set<String> testFilesToRead = new LinkedHashSet<>();
        if (knownBrokenPaths != null) {
            for (String rel : knownBrokenPaths) {
                if (LanguageSupportUtil.isTestFile(rel)) {
                    testFilesToRead.add(rel);
                }
            }
        }

        if (testFilesToRead.isEmpty()) {
            Matcher tf = Pattern.compile("([^\\s/]+(?:Test|Tests)?\\." + FILE_EXT_REGEX + ")").matcher(normalised);
            while (tf.find()) {
                String fileName = tf.group(1);
                for (VirtualFile vf : sourceFiles) {
                    if (vf.getName().equals(fileName)) {
                        testFilesToRead.add(relativePath(basePath, vf.getPath()));
                    }
                }
            }
        }

        for (String relPath : testFilesToRead) {
            appendFileContentIfExists(basePath, relPath, context);
        }

        for (VirtualFile vf : configFiles) {
            String rel = relativePath(basePath, vf.getPath());
            if (normalised.contains(vf.getName().toLowerCase()) || referencedFiles.contains(rel)) {
                appendFileContentIfExists(basePath, rel, context);
            }
        }

        return context.toString();
    }

    /**
     * Derives the JVM test class simple name from the first applied test file,
     * so the runner can execute only the generated test instead of the full suite.
     */
    static String testClassNameFromPaths(List<String> appliedFiles) {
        if (appliedFiles == null) return null;
        for (String path : appliedFiles) {
            if (path == null) continue;
            String normalized = path.replace("\\", "/");
            if (!LanguageSupportUtil.isTestFile(normalized)) continue;
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            int dot = fileName.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = fileName.substring(dot);
            if (ext.equals(".java") || ext.equals(".kt") || ext.equals(".scala") || ext.equals(".groovy")) {
                return fileName.substring(0, dot);
            }
        }
        return null;
    }

    static boolean isFileOpIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("edit") || lower.contains("modify") || lower.contains("update") ||
               lower.contains("create") || lower.contains("delete") || lower.contains("change") ||
               lower.contains("write") || lower.contains("fix") || lower.contains("remove") ||
               lower.contains("rename") || lower.contains("replace") || lower.contains("refactor") ||
               lower.contains("implement") ||
               lower.contains("run test") || lower.contains("execute test") || lower.contains("check test") ||
               lower.contains("add test") || lower.contains("missing test") || lower.contains("test case") ||
               (lower.contains("add") && (lower.contains("file") || lower.contains("class") || lower.contains("method")));
    }

    static boolean isRunTestsIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("run all tests")
                || lower.contains("run all test case")
                || lower.contains("execute all tests")
                || lower.contains("execute all test case")
                || lower.contains("run tests")
                || lower.contains("execute tests")
                || lower.contains("run test case")
                || lower.contains("execute test case")
                || lower.contains("show me result")
                || lower.contains("show test result")
                || lower.contains("run test suite")
                || lower.contains("mvn test")
                || lower.contains("gradle test")
                || lower.contains("npm test")
                || lower.contains("cargo test")
                || lower.contains("pytest")
                || lower.contains("dotnet test");
    }

    static boolean isGitAddCreatedFilesIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return (lower.contains("add it to git") || lower.contains("add them to git") ||
                lower.contains("add created files") || lower.contains("git add") ||
                lower.contains("stage the files") || lower.contains("stage created files"))
                && (lower.contains("created file") || lower.contains("new file") ||
                    lower.contains("what files") || lower.contains("these files") ||
                    lower.contains("them") || lower.contains("it"));
    }

    static boolean isCommitReviewIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        boolean reviewWord = lower.contains("review") || lower.contains("code review") || lower.contains("audit");
        boolean commitWord = lower.contains("commit") || lower.contains("last commit") || lower.contains("git commit")
                || lower.contains("change set") || lower.contains("diff");
        boolean jiraWord = lower.contains("jira") || lower.contains("ticket") || lower.contains("issue");
        boolean commentWord = lower.contains("comment") || lower.contains("review note") || lower.contains("review comments");
        return reviewWord && (commitWord || jiraWord || commentWord);
    }

    static boolean isReadmeIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("readme")
                || lower.contains("project documentation")
                || lower.contains("write documentation")
                || lower.contains("create documentation")
                || lower.contains("generate documentation");
    }

    static boolean isAnalysisIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("project understanding")
                || lower.contains("project architecture")
                || lower.contains("folder structure")
                || lower.contains("dependency graph")
                || lower.contains("module relationships")
                || lower.contains("external services")
                || lower.contains("message brokers")
                || lower.contains("message broker")
                || lower.contains("build system")
                || lower.contains("entry point")
                || lower.contains("current file")
                || lower.contains("related files")
                || lower.contains("dependency analysis")
                || lower.contains("security review")
                || lower.contains("performance review")
                || lower.contains("build failure")
                || lower.contains("compilation errors")
                || lower.contains("screenshot understanding")
                || lower.contains("screenshot")
                || lower.contains("ide context")
                || lower.contains("project memory")
                || lower.contains("autonomous mode")
                || lower.contains("multi-step planning")
                || lower.contains("context awareness");
    }

    static boolean isProjectStructureIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        boolean mentionsStructure = lower.contains("structure")
                || lower.contains("strucure")
                || lower.contains("project structure")
                || lower.contains("project strucure")
                || lower.contains("directory tree")
                || lower.contains("folder tree")
                || lower.contains("tree format")
                || lower.contains("project tree")
                || lower.contains("file tree");
        boolean asksToShow = lower.contains("show")
                || lower.contains("list")
                || lower.contains("display")
                || lower.contains("print")
                || lower.contains("see");
        return mentionsStructure && asksToShow;
    }

    static boolean isStructureOnlyResponse(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return isProjectStructureIntent(userText)
                || (lower.contains("show me") && (lower.contains("tree") || lower.contains("structure") || lower.contains("strucure")));
    }

    static boolean isStructureListingCommand(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase();
        return lower.matches("^(tree|ls|dir)(\\s|$).*")
                || lower.contains("find .")
                || lower.contains("get-childitem")
                || lower.contains("gci")
                || lower.contains("ls -l")
                || lower.contains("ls -la")
                || lower.contains("tree -l")
                || lower.contains("tree /f")
                || lower.contains("tree /a")
                || lower.contains("dir /s");
    }

    static boolean isClarificationRequest(String response) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        return lower.contains("could you clarify")
                || lower.contains("could you please provide")
                || lower.contains("please provide")
                || lower.contains("need more information")
                || lower.contains("i need more information")
                || lower.contains("i need access")
                || lower.contains("i don't have access")
                || lower.contains("i do not have access")
                || lower.contains("which repository")
                || lower.contains("which repo")
                || lower.contains("repository or directory")
                || lower.contains("share the path")
                || lower.contains("provide the path")
                || lower.contains("need the path")
                || lower.contains("without access to")
                || lower.contains("i cannot determine")
                || lower.contains("i can't determine")
                || lower.contains("i need your")
                || lower.contains("i would need");
    }

    static boolean isShellOrToolResponse(String response) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        return lower.contains("<execute_command")
                || lower.contains("<run>")
                || lower.contains("<run_command")
                || lower.contains("<repository_search")
                || lower.contains("<tool_call")
                || lower.contains("<tool_code")
                || lower.contains("```bash")
                || lower.contains("```sh")
                || lower.contains("```shell")
                || lower.contains("grep -r")
                || lower.contains("find .")
                || lower.contains("ls -la")
                || lower.contains("tree -l")
                || lower.contains("git log -1")
                || lower.contains("get-childitem")
                || lower.contains("dir /s")
                || lower.matches("(?s)^\\s*(ls|dir|tree|find|grep|git log|get-childitem)\\b.*");
    }

    static boolean isNonActionableModelResponse(String response) {
        return isClarificationRequest(response) || isShellOrToolResponse(response);
    }

    static String stripProjectStructureWrappers(String response) {
        if (response == null || response.isBlank()) return response;
        String cleaned = response;
        cleaned = cleaned.replaceAll("(?is)<\\/?PROJECT_TREE\\s*>", "");
        cleaned = cleaned.replaceAll("(?is)<\\/?PROJECT_STRUCTURE\\s*>", "");
        cleaned = cleaned.replaceAll("(?is)<PROJECT_TREE[^>]*>", "");
        cleaned = cleaned.replaceAll("(?is)<PROJECT_STRUCTURE[^>]*>", "");
        return cleaned.trim();
    }

    static String telemetryAnnouncement(String activity) {
        if (activity == null || activity.isBlank()) return "";
        String lower = activity.toLowerCase();
        if (lower.contains("plan")) return "Planning the next step…";
        if (lower.contains("think")) return "Thinking through the request…";
        if (lower.contains("work")) return "Working on changes…";
        if (lower.contains("debug")) return "Debugging and checking errors…";
        if (lower.contains("test")) return "Running tests…";
        if (lower.contains("review")) return "Reviewing the diff…";
        if (lower.contains("command") || lower.contains("run")) return "Running a command…";
        if (lower.contains("interrupt")) return "Interrupted.";
        if (lower.contains("idle") || lower.contains("ready")) return "Ready.";
        return activity + "…";
    }

    static String formatAttachmentTitle(AttachmentData attachment) {
        if (attachment == null) return "";
        String title = attachment.displayName();
        if (title == null || title.isBlank()) {
            title = "Untitled attachment";
        }
        return title;
    }

    static String formatAttachmentMeta(AttachmentData attachment) {
        if (attachment == null) return "";
        List<String> parts = new ArrayList<>();
        if (attachment.image()) {
            parts.add("Image");
        } else {
            parts.add("File");
        }
        if (attachment.mimeType() != null && !attachment.mimeType().isBlank()) {
            parts.add(attachment.mimeType());
        }
        parts.add(humanReadableBytes(attachment.sizeBytes()));
        return String.join(" • ", parts);
    }

    static String humanReadableBytes(long sizeBytes) {
        if (sizeBytes <= 0) {
            return "0 B";
        }
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        double size = sizeBytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024.0;
            unitIndex++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", size, units[Math.max(unitIndex, 0)]);
    }

    static boolean isSkillUpdateIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("update your skill set")
                || lower.contains("update skill set")
                || lower.contains("learn this solution")
                || lower.contains("remember this solution")
                || lower.contains("save this skill")
                || lower.contains("save this pattern")
                || lower.contains("learn this pattern")
                || lower.contains("store this skill")
                || lower.contains("teach you this");
    }

    static List<String> recordPromptHistory(List<String> history, String prompt, int limit) {
        List<String> updated = history == null ? new ArrayList<>() : new ArrayList<>(history);
        if (prompt == null) return updated;

        String trimmed = prompt.trim();
        if (trimmed.isEmpty()) return updated;
        if (!updated.isEmpty() && updated.get(updated.size() - 1).equals(trimmed)) return updated;

        updated.add(trimmed);
        while (updated.size() > Math.max(1, limit)) {
            updated.remove(0);
        }
        return updated;
    }

    static PromptHistoryState navigatePromptHistory(List<String> history,
                                                    String currentText,
                                                    int currentIndex,
                                                    String draft,
                                                    boolean up) {
        List<String> entries = history == null ? List.of() : history;
        String safeCurrentText = currentText == null ? "" : currentText;
        String safeDraft = draft == null ? "" : draft;
        if (entries.isEmpty()) {
            return new PromptHistoryState(safeCurrentText, -1, safeDraft);
        }

        if (currentIndex < 0) {
            if (up) {
                return new PromptHistoryState(entries.get(entries.size() - 1), entries.size() - 1, safeCurrentText);
            }
            return new PromptHistoryState(safeCurrentText, -1, safeDraft);
        }

        if (up) {
            int nextIndex = Math.max(0, currentIndex - 1);
            return new PromptHistoryState(entries.get(nextIndex), nextIndex, safeDraft);
        }

        if (currentIndex >= entries.size() - 1) {
            return new PromptHistoryState(safeDraft, -1, safeDraft);
        }

        int nextIndex = currentIndex + 1;
        return new PromptHistoryState(entries.get(nextIndex), nextIndex, safeDraft);
    }

    static String buildReadmeContext(Project project) {
        if (project == null || project.getBasePath() == null) return "";
        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("# README context\n");
        sb.append("- Project type: ").append(projectTypeLabel(project)).append("\n");
        String envSummary = EnvironmentInfoCollector.collectForPrompt(project);
        String selectedEnvLines = envSummary.lines()
                .filter(line -> line.startsWith("- **Build tool**")
                        || line.startsWith("- **Primary language**")
                        || line.startsWith("- **Test framework hint**")
                        || line.startsWith("- **Container stack**"))
                .collect(Collectors.joining("\n"));
        if (selectedEnvLines.isBlank()) {
            sb.append("- Primary language: ").append(LanguageSupportUtil.detectPrimaryLanguage(project)).append("\n");
        } else {
            sb.append(selectedEnvLines).append("\n");
        }
        String containerSummary = ContainerProjectUtil.buildPromptSummary(basePath);
        if (!containerSummary.isBlank()) {
            sb.append("- Container files:\n");
            sb.append(containerSummary).append("\n");
        }
        String structure = ProjectContextUtil.getProjectContext(project, false);
        if (!structure.isBlank()) {
            sb.append("- Project structure preview:\n");
            sb.append(structure.length() > 6000 ? structure.substring(0, 6000) + "\n[...truncated]" : structure);
        }
        return sb.toString().trim();
    }

    static String buildProjectAnalysisContext(Project project) {
        if (project == null || project.getBasePath() == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("# Project Analysis Context\n");
        sb.append("- Project type: ").append(projectTypeLabel(project)).append("\n");
        String envSummary = EnvironmentInfoCollector.collectForPrompt(project);
        String selectedEnvLines = envSummary.lines()
                .filter(line -> line.startsWith("- **Build tool**")
                        || line.startsWith("- **Primary language**")
                        || line.startsWith("- **Test framework hint**")
                        || line.startsWith("- **Container stack**"))
                .collect(Collectors.joining("\n"));
        if (selectedEnvLines.isBlank()) {
            sb.append("- Primary language: ").append(LanguageSupportUtil.detectPrimaryLanguage(project)).append("\n");
        } else {
            sb.append(selectedEnvLines).append("\n");
        }
        String structure = ProjectContextUtil.getProjectContext(project, false);
        if (!structure.isBlank()) {
            sb.append("- Tree preview:\n");
            sb.append(structure.length() > 5000 ? structure.substring(0, 5000) + "\n[...truncated]" : structure).append("\n");
        }
        String containerSummary = ContainerProjectUtil.buildPromptSummary(project.getBasePath());
        if (!containerSummary.isBlank()) {
            sb.append("- Container files:\n").append(containerSummary).append("\n");
        }
        return sb.toString().trim();
    }

    static String buildProjectStructureContext(Project project) {
        if (project == null || project.getBasePath() == null) return "";
        String structure = ProjectContextUtil.getProjectContext(project, false);
        if (structure.isBlank()) return "";
        return "# Project Structure\n" + structure.trim();
    }

    static String formatProjectStructureResponse(Project project) {
        String structure = buildProjectStructureContext(project);
        if (structure.isBlank()) return "";
        return "Current Project Structure\n"
                + "Filtered view: IDE/build/generated artifacts are excluded.\n\n"
                + structure.trim();
    }

    static String canonicalActivityPhase(String activity) {
        if (activity == null || activity.isBlank()) return "Ready";
        String lower = activity.toLowerCase();
        if (lower.contains("plan")) return "Planning";
        if (lower.contains("think")) return "Thinking";
        if (lower.contains("work")) return "Working";
        if (lower.contains("debug")) return "Debugging";
        if (lower.contains("test")) return "Testing";
        if (lower.contains("review")) return "Reviewing";
        if (lower.contains("command") || lower.contains("run")) return "Running";
        if (lower.contains("interrupt")) return "Interrupted";
        if (lower.contains("idle") || lower.contains("ready")) return "Ready";
        return activity;
    }

    static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty() || chunkSize <= 0) return chunks;
        int len = text.length();
        for (int i = 0; i < len; i += chunkSize) {
            chunks.add(text.substring(i, Math.min(len, i + chunkSize)));
        }
        return chunks;
    }

    static List<ChatMessage> trimConversationHistory(List<ChatMessage> history) {
        return trimConversationHistory(history, DEFAULT_CONTEXT_BUDGET_TOKENS);
    }

    static List<ChatMessage> trimConversationHistory(List<ChatMessage> history, int maxTokens) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        if (maxTokens <= 0) {
            return new ArrayList<>(history);
        }

        List<ChatMessage> retained = new ArrayList<>();
        int totalTokens = 0;

        ChatMessage systemMessage = history.get(0);
        if (systemMessage != null) {
            retained.add(systemMessage);
            totalTokens += estimateTokens(systemMessage.content());
        }

        List<ChatMessage> leadingContext = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            if (message != null && isContextMessage(message.content())) {
                leadingContext.add(message);
            } else {
                break;
            }
        }

        List<ChatMessage> tail = new ArrayList<>();
        for (int i = history.size() - 1; i >= 1; i--) {
            ChatMessage message = history.get(i);
            if (message == null || isContextMessage(message.content())) {
                continue;
            }
            int tokens = estimateTokens(message.content());
            if (totalTokens > 0 && totalTokens + tokens > maxTokens && !tail.isEmpty()) {
                continue;
            }
            tail.add(0, message);
            totalTokens += tokens;
        }

        for (ChatMessage message : leadingContext) {
            int tokens = estimateTokens(message.content());
            if (totalTokens + tokens > maxTokens && !retained.isEmpty()) {
                continue;
            }
            retained.add(message);
            totalTokens += tokens;
        }

        retained.addAll(tail);
        if (retained.size() > history.size()) {
            return new ArrayList<>(history);
        }
        return retained;
    }

    static int contextUsagePercent(List<ChatMessage> history, int maxTokens) {
        if (maxTokens <= 0) return 0;
        int used = estimateTokens(history);
        return Math.min(100, Math.max(0, (int) Math.round((used * 100.0) / maxTokens)));
    }

    static boolean isContextMessage(String content) {
        if (content == null) return false;
        return content.contains("Retrieved Context")
                || content.contains("Received context part")
                || content.contains("Project Context")
                || content.contains("Received project context");
    }

    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (text.trim().length() + 3) / 4);
    }

    static int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (ChatMessage message : messages) {
            if (message == null) continue;
            total += estimateTokens(message.content());
        }
        return total;
    }

    static String formatWorkspaceStatus(String projectTypeLabel, int inputTokens, int outputTokens) {
        String project = projectTypeLabel == null || projectTypeLabel.isBlank()
                ? "Unknown project"
                : projectTypeLabel;
        return "Working on: " + project
                + " | Tokens in/out: ~" + String.format("%,d", Math.max(0, inputTokens))
                + " / ~" + String.format("%,d", Math.max(0, outputTokens))
                + " (est.)";
    }

    static String strictFileOpReminder() {
        return "RESPONSE REJECTED: your previous reply contained NO file operation tag, so nothing was applied "
                + "and the failure is still unfixed.\n"
                + "Do NOT explain, apologize, or describe the fix in prose.\n"
                + "Reply with EXACTLY ONE tag containing the FULL corrected file, using the same path "
                + "given in the previous fix instructions:\n"
                + "<MODIFY_FILE path=\"src/test/java/.../YourTest.java\">\n"
                + "...complete corrected file content...\n"
                + "</MODIFY_FILE>\n"
                + "Your entire response must be that single tag and nothing else.";
    }

    static String llmStateSummary(boolean reachable, List<String> models, String selectedModel) {
        if (!reachable) return "LLM offline — server unreachable";
        if (models == null || models.isEmpty()) return "LLM online — no model loaded";
        String selected = selectedModel == null ? "" : selectedModel.trim();
        boolean selectedLoaded = !selected.isEmpty()
                && models.stream().anyMatch(m -> m != null && m.equalsIgnoreCase(selected));
        if (!selectedLoaded) {
            return "LLM online — \"" + selected + "\" not loaded (loaded: " + String.join(", ", models) + ")";
        }
        return "LLM ready — " + selected;
    }

    static String projectTypeLabel(Project project) {
        String basePath = project == null ? null : project.getBasePath();
        if (basePath == null) return "Unknown project";

        List<String> labels = new ArrayList<>();
        if (Files.exists(Path.of(basePath, "pom.xml"))) labels.add("Maven");
        if (Files.exists(Path.of(basePath, "build.gradle")) || Files.exists(Path.of(basePath, "build.gradle.kts"))) labels.add("Gradle");
        if (Files.exists(Path.of(basePath, "go.mod"))) labels.add("Go");
        if (Files.exists(Path.of(basePath, "Cargo.toml"))) labels.add("Rust");
        if (Files.exists(Path.of(basePath, "pyproject.toml")) || Files.exists(Path.of(basePath, "requirements.txt"))) labels.add("Python");
        if (Files.exists(Path.of(basePath, "package.json"))) labels.add("Node.js");
        if (Files.exists(Path.of(basePath, "composer.json"))) labels.add("PHP");
        if (Files.exists(Path.of(basePath, "Gemfile"))) labels.add("Ruby");
        if (Files.exists(Path.of(basePath, "tsconfig.json"))) labels.add("TypeScript");
        if (hasAnyExtension(basePath, ".sln") || hasAnyExtension(basePath, ".csproj")) labels.add(".NET");
        if (ContainerProjectUtil.hasDockerArtifacts(basePath)) labels.add("Docker");
        if (ContainerProjectUtil.hasHelmArtifacts(basePath)) labels.add("Helm");
        String primaryLanguage = displayLanguageName(LanguageSupportUtil.detectPrimaryLanguage(project));
        if (labels.isEmpty()) return primaryLanguage;
        return String.join(" + ", labels) + " / " + primaryLanguage;
    }

    static boolean isDockerOrHelmIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("docker")
                || lower.contains("dockerfile")
                || lower.contains("docker compose")
                || lower.contains("compose.yaml")
                || lower.contains("compose.yml")
                || lower.contains("helm")
                || lower.contains("chart.yaml")
                || lower.contains("chart.yml")
                || lower.contains("kubernetes")
                || lower.contains("containerize");
    }

    static String buildContainerContext(Project project) {
        if (project == null || project.getBasePath() == null) return "";
        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        String docker = ContainerProjectUtil.buildPromptSummary(basePath);
        if (docker.isBlank()) return "";
        sb.append("# Container files\n").append(docker.trim());
        return sb.toString();
    }

    private static String displayLanguageName(LanguageSupportUtil.Language language) {
        return switch (language) {
            case JAVA -> "Java";
            case KOTLIN -> "Kotlin";
            case GO -> "Go";
            case PYTHON -> "Python";
            case JAVASCRIPT -> "JavaScript";
            case TYPESCRIPT -> "TypeScript";
            case SCALA -> "Scala";
            case RUST -> "Rust";
            case PHP -> "PHP";
            case RUBY -> "Ruby";
            case CSHARP -> "C#";
            default -> "Unknown";
        };
    }

    private static void appendFileContentIfExists(String basePath, String relPath, StringBuilder context) {
        try {
            Path abs = isAbsolutePath(relPath)
                    ? Paths.get(relPath.replace("/", File.separator))
                    : Paths.get(basePath, relPath.replace("/", File.separator));
            if (Files.exists(abs)) {
                String content = Files.readString(abs, StandardCharsets.UTF_8);
                String label = Paths.get(relPath).getFileName().toString();
                context.append("=== ").append(label).append(" (current content — fix this file) ===\n")
                       .append(content).append("\n\n");
            }
        } catch (Exception ignored) {}
    }

    private static String normalizeRelativePath(String path) {
        String normalised = path.replace("\\", "/");
        int srcIdx = normalised.indexOf("/src/");
        if (srcIdx >= 0) {
            return normalised.substring(srcIdx + 1);
        }
        return normalised;
    }

    private static String relativePath(String basePath, String absolutePath) {
        return Paths.get(basePath).relativize(Paths.get(absolutePath)).toString().replace("\\", "/");
    }

    private static boolean isAbsolutePath(String path) {
        return path.matches("^[A-Za-z]:/.*") || path.startsWith("/");
    }

    private static boolean hasAnyExtension(String basePath, String extension) {
        File[] files = new File(basePath).listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.getName().endsWith(extension)) return true;
        }
        return false;
    }
}
