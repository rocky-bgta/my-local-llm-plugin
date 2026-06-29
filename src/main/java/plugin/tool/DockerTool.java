package plugin.tool;

import com.intellij.openapi.project.Project;

public class DockerTool {

    private final TerminalTool terminal;

    public DockerTool(Project project) {
        this.terminal = new TerminalTool(project);
    }

    public TerminalTool.Result build(String tag, String dockerfilePath) {
        return terminal.execute("docker build -t " + tag + " -f " + dockerfilePath + " .", 300);
    }

    public TerminalTool.Result run(String image, String... args) {
        String argStr = String.join(" ", args);
        return terminal.execute("docker run " + argStr + " " + image, 120);
    }

    public TerminalTool.Result images() {
        return terminal.execute("docker images --format '{{.Repository}}:{{.Tag}}'", 15);
    }

    public TerminalTool.Result ps() {
        return terminal.execute("docker ps --format '{{.Names}} {{.Status}}'", 15);
    }

    public TerminalTool.Result logs(String container) {
        return terminal.execute("docker logs --tail=50 " + container, 15);
    }

    public TerminalTool.Result stop(String container) {
        return terminal.execute("docker stop " + container, 30);
    }

    public TerminalTool.Result isAvailable() {
        return terminal.execute("docker version --format '{{.Server.Version}}'", 10);
    }
}
