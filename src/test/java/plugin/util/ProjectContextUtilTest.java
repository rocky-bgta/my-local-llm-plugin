package plugin.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProjectContextUtilTest {

    @Test
    void rendersOnlyActualProjectFilesAndFolders() {
        ProjectContextUtil.TreeNode structure = new ProjectContextUtil.TreeNode("demo-project", true, List.of(
                new ProjectContextUtil.TreeNode(".idea", true, List.of(
                        new ProjectContextUtil.TreeNode("workspace.xml", false, List.of())
                )),
                new ProjectContextUtil.TreeNode("build", true, List.of(
                        new ProjectContextUtil.TreeNode("generated.txt", false, List.of())
                )),
                new ProjectContextUtil.TreeNode("generated-sources", true, List.of(
                        new ProjectContextUtil.TreeNode("Generated.java", false, List.of())
                )),
                new ProjectContextUtil.TreeNode("src", true, List.of(
                        new ProjectContextUtil.TreeNode("main", true, List.of(
                                new ProjectContextUtil.TreeNode("java", true, List.of(
                                        new ProjectContextUtil.TreeNode("App.java", false, List.of())
                                ))
                        ))
                )),
                new ProjectContextUtil.TreeNode("README.md", false, List.of())
        ));

        String rendered = renderTree(structure);

        assertTrue(rendered.contains("demo-project/"));
        assertTrue(rendered.contains("src/"));
        assertTrue(rendered.contains("App.java"));
        assertTrue(rendered.contains("README.md"));
        assertFalse(rendered.contains(".idea"));
        assertFalse(rendered.contains("build/"));
        assertFalse(rendered.contains("generated-sources"));
        assertFalse(rendered.contains("workspace.xml"));
    }

    @Test
    void excludesCommonBuildArtifactsByName() {
        assertTrue(ProjectContextUtil.shouldExclude("build"));
        assertTrue(ProjectContextUtil.shouldExclude("target"));
        assertTrue(ProjectContextUtil.shouldExclude("out"));
        assertTrue(ProjectContextUtil.shouldExclude(".idea"));
    }

    private static String renderTree(ProjectContextUtil.TreeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.name()).append("/\n");
        ProjectContextUtil.appendTree(node, "", sb, 16000);
        return sb.toString();
    }

}
