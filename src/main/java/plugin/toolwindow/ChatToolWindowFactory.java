package plugin.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import plugin.ui.ChatPanel;

public class ChatToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ChatPanel panel = new ChatPanel(project);
        Content content = ContentFactory.getInstance()
                .createContent(panel.getSwingComponent(), "New Chat", false);
        panel.setTabContent(content);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(content, panel);
    }
}
