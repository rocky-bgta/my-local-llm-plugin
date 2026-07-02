package plugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class OpenChatAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        openChat(e.getProject());
    }

    void openChat(Project project) {
        if (project != null) {
            showToolWindow(project);
        }
    }

    protected void showToolWindow(@NotNull Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local LLM");
        if (toolWindow != null) toolWindow.show(null);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(isEnabled(e.getProject()));
    }

    boolean isEnabled(Project project) {
        return project != null;
    }
}
