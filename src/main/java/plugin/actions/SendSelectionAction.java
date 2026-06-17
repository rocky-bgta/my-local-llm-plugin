package plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import plugin.ui.ChatPanel;

public class SendSelectionAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;

        String selection = editor.getSelectionModel().getSelectedText();
        if (selection == null || selection.isBlank()) return;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Local LLM Assistant");
        if (toolWindow == null) return;

        toolWindow.show(() -> {
            Content content = toolWindow.getContentManager().getContent(0);
            if (content == null) return;
            java.awt.Component component = content.getComponent();
            if (component instanceof ChatPanel chatPanel) {
                chatPanel.sendText(selection);
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasSelection = editor != null
                && editor.getSelectionModel().hasSelection();
        e.getPresentation().setEnabled(hasSelection);
    }
}
