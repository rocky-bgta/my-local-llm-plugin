package plugin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandIntentUtilTest {

    @Test
    void detectsUnixOnlyCommands() {
        assertTrue(CommandIntentUtil.isUnixOnlyCommand("grep -rni 'class ConversationManager' src/main/java/ --include='*.java'"));
        assertTrue(CommandIntentUtil.isUnixOnlyCommand("find . -name '*.java'"));
        assertTrue(CommandIntentUtil.isUnixOnlyCommand("sed -n '1,10p' file.txt"));
    }

    @Test
    void ignoresPowerShellCommands() {
        assertFalse(CommandIntentUtil.isUnixOnlyCommand("Get-ChildItem -Recurse"));
        assertFalse(CommandIntentUtil.isUnixOnlyCommand("Select-String -Pattern foo"));
    }

    @Test
    void detectsWindowsOnlyCommands() {
        assertTrue(CommandIntentUtil.isWindowsOnlyCommand("Get-ChildItem -Recurse"));
        assertTrue(CommandIntentUtil.isWindowsOnlyCommand("powershell -Command Get-Process"));
        assertFalse(CommandIntentUtil.isWindowsOnlyCommand("grep -rni foo ."));
    }
}
