package plugin.testing;

import java.util.Map;

/**
 * Structured error extracted from a single build or test run failure.
 * Every field maps 1-to-1 to a placeholder in the repair prompt template.
 */
public record ErrorContext(
        String failedCommand,
        String errorSnippet,
        String affectedFilePath,
        int affectedLine,
        String affectedFileContent,
        Map<String, String> relatedFileContents,
        String rootCauseGroup
) {
    /** Fallback when raw output cannot be parsed into a structured error. */
    public static ErrorContext unknown(String failedCommand, String rawOutput) {
        return new ErrorContext(
                failedCommand == null ? "" : failedCommand,
                rawOutput    == null ? "" : rawOutput,
                "", 0, "", Map.of(), "");
    }

    public boolean hasLocation() {
        return affectedFilePath != null && !affectedFilePath.isBlank();
    }
}
