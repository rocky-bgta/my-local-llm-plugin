package plugin.tool;

import com.intellij.openapi.project.Project;

public class KubernetesTool {

    private final TerminalTool terminal;

    public KubernetesTool(Project project) {
        this.terminal = new TerminalTool(project);
    }

    public TerminalTool.Result getPods(String namespace) {
        return terminal.execute("kubectl get pods -n " + namespace, 15);
    }

    public TerminalTool.Result getLogs(String podName, String namespace) {
        return terminal.execute("kubectl logs " + podName + " -n " + namespace + " --tail=100", 15);
    }

    public TerminalTool.Result applyManifest(String path) {
        return terminal.execute("kubectl apply -f " + path, 60);
    }

    public TerminalTool.Result describeDeployment(String name, String namespace) {
        return terminal.execute("kubectl describe deployment " + name + " -n " + namespace, 15);
    }

    public TerminalTool.Result getServices(String namespace) {
        return terminal.execute("kubectl get services -n " + namespace, 15);
    }

    public TerminalTool.Result isAvailable() {
        return terminal.execute("kubectl version --client --short", 10);
    }
}
