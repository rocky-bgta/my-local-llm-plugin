package plugin.testing;

import plugin.agent.AgentContext;
import plugin.agent.AgentTask;
import plugin.psi.ClassFinder;
import plugin.psi.MethodFinder;
import plugin.util.LanguageSupportUtil;

import java.util.List;

public class TestGenerator {

    public String buildTestPrompt(AgentContext ctx, String targetClass) {
        LanguageSupportUtil.Language language = LanguageSupportUtil.detectPrimaryLanguage(ctx.getProject());
        List<MethodFinder.MethodInfo> methods = List.of();
        List<ClassFinder.ClassInfo> related = List.of();

        if (LanguageSupportUtil.isJvmLanguage(language)) {
            ClassFinder classFinder = new ClassFinder(ctx.getProject());
            MethodFinder methodFinder = new MethodFinder(ctx.getProject());
            methods = methodFinder.findPublicMethods(targetClass);
            related = classFinder.findRelatedClasses(targetClass);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate comprehensive ").append(LanguageSupportUtil.frameworkHint(language))
                .append(" tests for `").append(targetClass).append("`.\n\n");

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
                - Use the project's native test framework
                - Follow the language's usual testing style and conventions
                - Test happy path, edge cases, and error conditions
                - Place the file under the matching test location for the detected language
                - Use XML tag: <CREATE_FILE path="<test path>/""").append(targetClass)
                .append("""
                Test">...</CREATE_FILE>
                """);

        return prompt.toString();
    }

    public String inferTestClassName(String targetClass) {
        return targetClass + "Test";
    }

    public String inferTestPath(String targetClass) {
        return LanguageSupportUtil.suggestedTestPath("src/main/java/" + targetClass + ".java");
    }
}
