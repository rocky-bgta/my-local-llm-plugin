package plugin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LanguageSupportUtilTest {

    @Test
    void detectsCommonLanguagesFromPaths() {
        assertEquals(LanguageSupportUtil.Language.JAVA, LanguageSupportUtil.detectLanguage("src/main/java/app/App.java"));
        assertEquals(LanguageSupportUtil.Language.KOTLIN, LanguageSupportUtil.detectLanguage("src/main/kotlin/app/App.kt"));
        assertEquals(LanguageSupportUtil.Language.GO, LanguageSupportUtil.detectLanguage("cmd/server/main.go"));
        assertEquals(LanguageSupportUtil.Language.PYTHON, LanguageSupportUtil.detectLanguage("app/main.py"));
    }

    @Test
    void exposesFrameworkHints() {
        assertEquals("JUnit 5 / Kotest", LanguageSupportUtil.frameworkHint(LanguageSupportUtil.Language.JAVA));
        assertEquals("Go testing package", LanguageSupportUtil.frameworkHint(LanguageSupportUtil.Language.GO));
        assertEquals("pytest / unittest", LanguageSupportUtil.frameworkHint(LanguageSupportUtil.Language.PYTHON));
    }

    @Test
    void suggestsLanguageSpecificTestPaths() {
        assertEquals("src/test/java/app/AppTest.java",
                LanguageSupportUtil.suggestedTestPath("src/main/java/app/App.java"));
        assertEquals("src/test/java/app/AppTest.java",
                LanguageSupportUtil.suggestedTestPath("src/test/java/app/AppTest.java"));
        assertEquals("src/test/kotlin/app/AppTest.kt",
                LanguageSupportUtil.suggestedTestPath("src/main/kotlin/app/App.kt"));
        assertEquals("cmd/server/main_test.go",
                LanguageSupportUtil.suggestedTestPath("cmd/server/main.go"));
        assertEquals("tests/test_main.py",
                LanguageSupportUtil.suggestedTestPath("app/main.py"));
        assertEquals("src/components/Button.test.ts",
                LanguageSupportUtil.suggestedTestPath("src/components/Button.ts"));
    }
}
