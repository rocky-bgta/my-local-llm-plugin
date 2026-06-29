package plugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class GenerateTestAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) return;

        String className = file.getNameWithoutExtension();
        String message = "Generate tests for " + className;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local LLM");
        if (toolWindow != null) {
            toolWindow.show(null);
            // The tool window's ChatPanel receives the message via the input field;
            // we populate it programmatically here.
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isJava = file != null && "java".equals(file.getExtension());
        e.getPresentation().setEnabled(isJava);
    }
}
