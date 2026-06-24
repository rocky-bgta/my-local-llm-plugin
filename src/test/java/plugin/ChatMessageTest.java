package plugin;

import org.junit.jupiter.api.Test;
import plugin.llm.model.ChatMessage;

import static org.junit.jupiter.api.Assertions.*;

public class ChatMessageTest {

    @Test
    void recordFieldsAreAccessible() {
        ChatMessage message = new ChatMessage("user", "Hello");
        assertEquals("user", message.role());
        assertEquals("Hello", message.content());
    }

    @Test
    void equalityHoldsForSameValues() {
        ChatMessage m1 = new ChatMessage("user", "Hello");
        ChatMessage m2 = new ChatMessage("user", "Hello");
        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
    }

    @Test
    void inequalityForDifferentRole() {
        ChatMessage m1 = new ChatMessage("user", "Hello");
        ChatMessage m2 = new ChatMessage("assistant", "Hello");
        assertNotEquals(m1, m2);
    }


}
