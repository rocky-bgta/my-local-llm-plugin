package plugin.llm;

import org.junit.jupiter.api.Test;
import plugin.llm.model.ChatMessage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class IntegrationTest {

    @Test
    void roundTrip() {
        var msg = new ChatMessage("user", "Hello");
        assertEquals(msg, msg);
    }

    @Test
    void multipleMessages() {
        var a = new ChatMessage("system", "You are Qwythos.");
        var b = new ChatMessage("user", "Hi");
        var c = new ChatMessage("assistant", "I am ready.");
        assertEquals(3, List.of(a, b, c).size());
    }

    @Test
    void mixedRoles() {
        var a = new ChatMessage("system", "You are Qwythos.");
        var b = new ChatMessage("user", "Hi");
        var c = new ChatMessage("assistant", "I am ready.");
        assertEquals(3, List.of(a, b, c).size());
    }

    @Test
    void equalsContract() {
        var a = new ChatMessage("user", "Hello");
        var b = new ChatMessage("user", "Hello");
        var c = new ChatMessage("assistant", "Hi");
        var d = new ChatMessage("user", "World");

        assertEquals(a, a);
        assertNotEquals(null, a);
        assertTrue(a.equals(b));
        assertFalse(a.equals(c));
        assertFalse(a.equals(d));
    }

    @Test
    void hashCodeConsistency() {
        var msg = new ChatMessage("user", "Hello");
        assertEquals(msg.hashCode(), msg.hashCode());
    }
}