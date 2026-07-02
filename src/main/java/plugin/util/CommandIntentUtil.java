package plugin.util;

import java.util.Locale;

public final class CommandIntentUtil {

    private CommandIntentUtil() {}

    public static boolean isWindowsPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isUnixLikePlatform() {
        return !isWindowsPlatform();
    }

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

    public static boolean isUnixOnlyCommand(String command) {
        if (command == null) return false;

        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;

        return normalized.startsWith("grep ")
                || normalized.startsWith("grep\t")
                || normalized.equals("grep")
                || normalized.startsWith("find ")
                || normalized.equals("find")
                || normalized.startsWith("sed ")
                || normalized.equals("sed")
                || normalized.startsWith("awk ")
                || normalized.equals("awk")
                || normalized.startsWith("xargs ")
                || normalized.equals("xargs")
                || normalized.startsWith("head ")
                || normalized.equals("head")
                || normalized.startsWith("tail ")
                || normalized.equals("tail")
                || normalized.startsWith("cat ")
                || normalized.equals("cat");
    }

    public static boolean isWindowsOnlyCommand(String command) {
        if (command == null) return false;

        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;

        return normalized.startsWith("cmd ")
                || normalized.equals("cmd")
                || normalized.startsWith("powershell ")
                || normalized.equals("powershell")
                || normalized.startsWith("pwsh ")
                || normalized.equals("pwsh")
                || normalized.startsWith("get-childitem")
                || normalized.startsWith("select-string")
                || normalized.startsWith("select-object")
                || normalized.startsWith("where-object")
                || normalized.startsWith("measure-object");
    }

    public static boolean isCommandCompatibleWithCurrentPlatform(String command) {
        if (isWindowsPlatform()) {
            return !isUnixOnlyCommand(command);
        }
        return !isWindowsOnlyCommand(command);
    }
}
