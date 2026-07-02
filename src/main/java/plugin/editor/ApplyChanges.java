package plugin.editor;

import com.intellij.openapi.project.Project;
import plugin.util.FileOperationUtil;

import java.util.ArrayList;
import java.util.List;

public class ApplyChanges {

    private final FileEditor fileEditor;
    private final PatchGenerator patchGenerator;

    public ApplyChanges(Project project) {
        this.fileEditor = new FileEditor(project);
        this.patchGenerator = new PatchGenerator();
    }

    public ApplyResult apply(String llmResponse, com.intellij.openapi.project.Project project) {
        FileOperationUtil.FileOpResult opResult = FileOperationUtil.processFileOperations(
                project, llmResponse);

        List<String> applied = opResult.appliedFiles != null ? new ArrayList<>(opResult.appliedFiles) : new ArrayList<>();
        List<String> warnings = opResult.warnings != null ? new ArrayList<>(opResult.warnings) : new ArrayList<>();

        return new ApplyResult(applied, warnings, opResult.runTests, opResult.testName);
    }

    public record ApplyResult(
            List<String> appliedFiles,
            List<String> warnings,
            boolean shouldRunTests,
            String testName
    ) {
        public boolean hasChanges() { return !appliedFiles.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}
