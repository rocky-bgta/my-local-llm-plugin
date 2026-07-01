package plugin.util;

import java.util.Locale;

public final class CommandIntentUtil {

    private CommandIntentUtil() {}

    public static boolean isStructureInspectionCommand(String command) {
        if (command == null) return false;

        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;

        return normalized.equals("tree")
                || normalized.startsWith("tree ")
                || normalized.equals("dir")
                || normalized.startsWith("dir ")
                || normalized.equals("ls")
                || normalized.startsWith("ls ");
    }
}
