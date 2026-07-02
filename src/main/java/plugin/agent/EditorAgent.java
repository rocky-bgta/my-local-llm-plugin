package plugin.agent;

import com.intellij.openapi.project.Project;
import plugin.util.FileOperationUtil;

public class EditorAgent {

    public FileOperationUtil.FileOpResult apply(AgentContext ctx) {
        String response = ctx.getLlmResponse();
        if (response == null || response.isBlank()) {
            return emptyResult();
        }

        Project project = ctx.getProject();
        FileOperationUtil.FileOpResult result = FileOperationUtil.processFileOperations(project, response);

        if (result.appliedFiles != null) {
            result.appliedFiles.forEach(f -> ctx.getWorkingMemory().trackModifiedFile(f));
        }
        boolean hasChanges = (result.appliedFiles != null && !result.appliedFiles.isEmpty())
                || (result.warnings != null && !result.warnings.isEmpty());
        ctx.setEditApplied(hasChanges);

        return result;
    }

    private FileOperationUtil.FileOpResult emptyResult() {
        return new FileOperationUtil.FileOpResult(
                false, false, null, null,
                new java.util.ArrayList<>(), new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                new java.util.ArrayList<>(), new java.util.ArrayList<>()
        );
    }
}
