package plugin.llm;

import org.junit.jupiter.api.Test;
import plugin.llm.model.ChatMessage;
import static org.junit.jupiter.api.Assertions.*;

public class ChatMessageTest {

    @Test
    void constructor() {
        var msg = new ChatMessage("user", "Hello");
        assertEquals("user", msg.role());
        assertEquals("Hello", msg.content());
    }

    @Test
    void role() {
        var msg = new ChatMessage("system", "You are Qwythos.");
        assertEquals("system", msg.role());
    }

    @Test
    void content() {
        var msg = new ChatMessage("assistant", "I am ready.");
        assertEquals("I am ready.", msg.content());
    }

    @Test
    void toStringOutput() {
        var msg = new ChatMessage("user", "Hi");
        String expected = "ChatMessage(role=user, content=Hi)";
        assertEquals(expected, msg.toString());
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