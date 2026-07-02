package plugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import plugin.ui.ChatPanel;

public class GenerateTestAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) return;

        String className = file.getNameWithoutExtension();

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local LLM");
        if (toolWindow == null) return;

        // show() runs the callback after the tool window content (ChatPanel) is created,
        // so the panel is registered in project user data by the time we read it.
        toolWindow.show(() -> {
            ChatPanel panel = project.getUserData(ChatPanel.PANEL_KEY);
            if (panel != null) panel.generateTestsFor(file.getPath(), className);
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isJava = file != null && "java".equals(file.getExtension());
        e.getPresentation().setEnabled(isJava);
    }
}
