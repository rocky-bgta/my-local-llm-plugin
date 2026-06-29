package plugin.testing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controls the iterative compile/test → fix → retry cycle.
 *
 * Key behaviours:
 *  - Up to MAX_COMPILE_ATTEMPTS and MAX_TEST_ATTEMPTS (4 each).
 *  - Error fingerprinting detects when the LLM repeats the same failing fix
 *    so the prompt can instruct it to try a different approach.
 *  - inTestFixLoop flag: when set, a successful compile after a test-fix
 *    re-runs the tests instead of just reporting "build ok".
 */
public final class AutoFixLoop {

    public static final int MAX_COMPILE_ATTEMPTS = 4;
    public static final int MAX_TEST_ATTEMPTS    = 4;

    // Matches the first "[ERROR] …/Foo.java:[line,col] message" line
    private static final Pattern COMPILE_ERROR_FP = Pattern.compile(
            "\\[ERROR\\][^\\n]*?\\.java:\\[?(\\d+)[^\\]]*\\]([^\\n]{0,80})"
    );

    // Matches failing test method names: "Tests run: … Foo.testBar"
    private static final Pattern TEST_FAILURE_FP = Pattern.compile(
            "<<< FAILURE!|<<< ERROR!\\n([^\\n]+)"
    );
    private static final Pattern TEST_CLASS_FP = Pattern.compile(
            "\\[ERROR\\]\\s+(\\w+(?:Test|IT)\\.\\w+)"
    );

    private AutoFixLoop() {}

    // -------------------------------------------------------------------------
    // Fingerprint extraction
    // -------------------------------------------------------------------------

    /**
     * Returns a short signature of the first compile error (file + line + message).
     * Two equal fingerprints mean the LLM made no progress.
     */
    public static String extractCompileFingerprint(String errorOutput) {
        if (errorOutput == null || errorOutput.isBlank()) return "";
        Matcher m = COMPILE_ERROR_FP.matcher(errorOutput);
        if (!m.find()) return firstErrorLine(errorOutput);
        String raw = m.group(0).trim();
        return raw.length() > 120 ? raw.substring(0, 120) : raw;
    }

    /**
     * Returns a short signature of the failing test methods.
     */
    public static String extractTestFingerprint(String errorOutput) {
        if (errorOutput == null || errorOutput.isBlank()) return "";
        Matcher m = TEST_CLASS_FP.matcher(errorOutput);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (m.find() && n++ < 4) sb.append(m.group(1)).append(';');
        return sb.length() > 0 ? sb.toString() : firstErrorLine(errorOutput);
    }

    public static boolean isSameError(String previous, String current) {
        return previous != null && !previous.isBlank() && previous.equals(current);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String firstErrorLine(String output) {
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[ERROR]") && t.length() > 8) {
                return t.length() > 120 ? t.substring(0, 120) : t;
            }
        }
        return "";
    }
}
