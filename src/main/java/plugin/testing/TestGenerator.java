package plugin.testing;

import plugin.agent.AgentContext;
import plugin.agent.AgentTask;
import plugin.psi.ClassFinder;
import plugin.psi.MethodFinder;

import java.util.List;
import java.util.stream.Collectors;

public class TestGenerator {

    public String buildTestPrompt(AgentContext ctx, String targetClass) {
        ClassFinder classFinder = new ClassFinder(ctx.getProject());
        MethodFinder methodFinder = new MethodFinder(ctx.getProject());

        List<MethodFinder.MethodInfo> methods = methodFinder.findPublicMethods(targetClass);
        List<ClassFinder.ClassInfo> related = classFinder.findRelatedClasses(targetClass);

        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate comprehensive JUnit 5 tests for `").append(targetClass).append("`.\n\n");

        if (!methods.isEmpty()) {
            prompt.append("Public methods to test:\n");
            methods.forEach(m -> prompt.append("- `").append(m.methodName())
                    .append("(").append(m.parameters()).append(")`\n"));
            prompt.append("\n");
        }

        if (!related.isEmpty()) {
            prompt.append("Related classes (may need mocking):\n");
            related.stream().limit(5).forEach(c -> prompt.append("- ").append(c.className()).append("\n"));
            prompt.append("\n");
        }

        prompt.append("""
                Requirements:
                - Use JUnit 5 (@Test, @BeforeEach, @AfterEach)
                - Use Mockito for dependencies
                - Follow AAA pattern (Arrange, Act, Assert)
                - Test happy path, edge cases, and error conditions
                - Use package plugin in the test class
                - Place in src/test/java/plugin/
                - Use XML tag: <CREATE_FILE path="src/test/java/plugin/""").append(targetClass)
                .append("""
                Test.java">...</CREATE_FILE>
                """);

        return prompt.toString();
    }

    public String inferTestClassName(String targetClass) {
        return targetClass + "Test";
    }

    public String inferTestPath(String targetClass) {
        return "src/test/java/plugin/" + targetClass + "Test.java";
    }
}
