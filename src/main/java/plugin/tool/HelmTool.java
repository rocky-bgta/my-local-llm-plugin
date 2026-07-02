package plugin.tool;

import com.intellij.openapi.project.Project;

public class HelmTool {

    private final TerminalTool terminal;

    public HelmTool(Project project) {
        this.terminal = new TerminalTool(project);
    }

    public TerminalTool.Result version() {
        return terminal.execute("helm version --short", 15);
    }

    public TerminalTool.Result lint(String chartPath) {
        return terminal.execute("helm lint " + chartPath, 120);
    }

    public TerminalTool.Result template(String releaseName, String chartPath) {
        return terminal.execute("helm template " + releaseName + " " + chartPath, 120);
    }

    public TerminalTool.Result installOrUpgrade(String releaseName, String chartPath, String namespace) {
        return terminal.execute("helm upgrade --install " + releaseName + " " + chartPath + " -n " + namespace + " --create-namespace", 180);
    }

    public TerminalTool.Result list(String namespace) {
        return terminal.execute("helm list -n " + namespace, 15);
    }
}
