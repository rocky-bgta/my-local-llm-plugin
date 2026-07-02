package plugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class ExplainCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        explainSelectedCode(e.getProject(), e.getData(CommonDataKeys.EDITOR));
    }

    void explainSelectedCode(Project project, Editor editor) {
        if (project == null || editor == null) return;

        SelectionModel selection = editor.getSelectionModel();
        String selectedText = selection.getSelectedText();
        if (selectedText == null || selectedText.isBlank()) return;

        showToolWindow(project);
    }

    protected void showToolWindow(@NotNull Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local LLM");
        if (toolWindow != null) {
            toolWindow.show(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(isEnabled(e.getData(CommonDataKeys.EDITOR)));
    }

    boolean isEnabled(Editor editor) {
        return editor != null && editor.getSelectionModel().hasSelection();
    }
}
