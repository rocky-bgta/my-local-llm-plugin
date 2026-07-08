package plugin.ui;

/**
 * Filters the assistant token stream shown in the chat pane so file-operation
 * blocks (<CREATE_FILE>/<MODIFY_FILE>) appear as a one-line placeholder instead
 * of flooding the chat with raw file content. The unfiltered stream is still
 * buffered by the caller for parsing and file writes.
 *
 * Tokens can split a tag at any position, so text that could still become an
 * opening tag is held back until it is disambiguated.
 */
public class StreamDisplayMasker {

    private static final String[] OPEN_PREFIXES = {"<CREATE_FILE", "<MODIFY_FILE"};
    // If no '>' appears within this many chars of a tag start, treat it as plain text.
    private static final int MAX_OPEN_TAG_CHARS = 400;

    private final StringBuilder pending = new StringBuilder();
    private String closeTag = null;

    /** Appends a streamed token and returns whatever became safe to display. */
    public String feed(String token) {
        if (token != null) pending.append(token);
        return drain(false);
    }

    /** Flushes any held-back text at end of stream. */
    public String finish() {
        return drain(true);
    }

    public void reset() {
        pending.setLength(0);
        closeTag = null;
    }

    private String drain(boolean flush) {
        StringBuilder out = new StringBuilder();
        while (true) {
            if (closeTag != null) {
                int close = pending.indexOf(closeTag);
                if (close >= 0) {
                    pending.delete(0, close + closeTag.length());
                    closeTag = null;
                    continue;
                }
                if (flush) {
                    pending.setLength(0);
                } else if (pending.length() >= closeTag.length()) {
                    // Content is hidden — keep only a tail that could hold a split closing tag
                    pending.delete(0, pending.length() - closeTag.length() + 1);
                }
                return out.toString();
            }

            int lt = pending.indexOf("<");
            if (lt < 0) {
                out.append(pending);
                pending.setLength(0);
                return out.toString();
            }
            out.append(pending, 0, lt);
            pending.delete(0, lt);

            String tag = matchedOpenPrefix();
            if (tag == null) {
                if (!flush && couldBecomeOpenTag()) return out.toString();
                out.append('<');
                pending.deleteCharAt(0);
                continue;
            }
            int gt = pending.indexOf(">");
            if (gt < 0) {
                if (!flush && pending.length() <= MAX_OPEN_TAG_CHARS) return out.toString();
                out.append('<');
                pending.deleteCharAt(0);
                continue;
            }
            String path = extractPath(pending.substring(0, gt + 1));
            pending.delete(0, gt + 1);
            closeTag = "</" + tag.substring(1) + ">";
            out.append("⚙ ").append(tag.substring(1)).append(' ')
               .append(path.isBlank() ? "(no path)" : path)
               .append(" — content hidden, writing to file…\n");
        }
    }

    private String matchedOpenPrefix() {
        for (String p : OPEN_PREFIXES) {
            if (pending.length() >= p.length() && pending.substring(0, p.length()).equals(p)) {
                return p;
            }
        }
        return null;
    }

    private boolean couldBecomeOpenTag() {
        for (String p : OPEN_PREFIXES) {
            int n = Math.min(pending.length(), p.length());
            if (pending.substring(0, n).equals(p.substring(0, n))) return true;
        }
        return false;
    }

    private static String extractPath(String openTag) {
        int i = openTag.indexOf("path=\"");
        if (i < 0) return "";
        int start = i + "path=\"".length();
        int end = openTag.indexOf('"', start);
        return end < 0 ? "" : openTag.substring(start, end);
    }
}
