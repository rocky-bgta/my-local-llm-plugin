package plugin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BuildUtilTest {

    @Test
    void normalizesTreeCommandForWindows() {
        assertEquals("tree /F /A", BuildUtil.normalizeCustomCommand("tree -a --noreport", true));
    }

    @Test
    void leavesNonTreeCommandsUntouched() {
        assertEquals("mvn test", BuildUtil.normalizeCustomCommand("mvn test", true));
    }
}
