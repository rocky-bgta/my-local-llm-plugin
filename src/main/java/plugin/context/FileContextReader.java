package plugin.context;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class FileContextReader {

    private final Project project;

    public FileContextReader(Project project) {
        this.project = project;
    }

    public String getCurrentFileContent() {
        Editor editor = getCurrentEditor();
        if (editor == null) return null;
        return editor.getDocument().getText();
    }

    public String getSelectedText() {
        Editor editor = getCurrentEditor();
        if (editor == null) return null;
        return editor.getSelectionModel().getSelectedText();
    }

    public String getCurrentFilePath() {
        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length == 0) return null;
        return files[0].getPath();
    }

    public String getCurrentFileName() {
        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length == 0) return null;
        return files[0].getName();
    }

    public String readFile(VirtualFile file) throws IOException {
        if (!file.exists() || file.isDirectory()) {
            throw new IOException("Not a readable file: " + file.getPath());
        }
        byte[] bytes = file.contentsToByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String formatForContext(VirtualFile file) throws IOException {
        String content = readFile(file);
        String relativePath = getRelativePath(file);
        return "### " + relativePath + "\n```\n" + content + "\n```\n";
    }

    public boolean isSupportedTextFile(VirtualFile file) {
        if (file.isDirectory() || !file.exists()) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".go")
                || name.endsWith(".py") || name.endsWith(".xml") || name.endsWith(".json")
                || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".properties")
                || name.endsWith(".gradle") || name.endsWith(".md") || name.endsWith(".txt")
                || name.endsWith(".sql") || name.endsWith(".html") || name.endsWith(".css")
                || name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".sh")
                || name.endsWith(".toml");
    }

    private Editor getCurrentEditor() {
        return FileEditorManager.getInstance(project).getSelectedTextEditor();
    }

    private String getRelativePath(VirtualFile file) {
        String projectPath = project.getBasePath();
        String filePath = file.getPath();
        if (projectPath != null && filePath.startsWith(projectPath)) {
            return filePath.substring(projectPath.length() + 1);
        }
        return file.getName();
    }
}
