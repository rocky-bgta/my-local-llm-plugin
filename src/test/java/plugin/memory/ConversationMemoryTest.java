package plugin.memory;

import java.util.List;
import plugin.llm.model.ChatMessage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {

    @Test
    void testAddMessage() {
        ConversationMemory conversationMemory = new ConversationMemory();
        conversationMemory.addMessage("user", "Hello, world!");
        assertEquals(1, conversationMemory.size());
    }

    @Test
    void testAddRecentMessages() {
        ConversationMemory conversationMemory = new ConversationMemory();
        conversationMemory.addMessage("assistant", "This is an assistant message.");
        conversationMemory.addMessage("user", "I have a question.");
        conversationMemory.addMessage("assistant", "Sure, I can help with that.");
        assertEquals(3, conversationMemory.size());
    }

    @Test
    void testGetHistory() {
        ConversationMemory conversationMemory = new ConversationMemory();
        conversationMemory.addMessage("user", "Hello, world!");
        List<ChatMessage> history = conversationMemory.getHistory();
        assertNotNull(history);
        assertEquals(1, history.size());
    }

    @Test
    void testGetRecentHistory() {
        ConversationMemory conversationMemory = new ConversationMemory();
        conversationMemory.addMessage("assistant", "This is an assistant message.");
        conversationMemory.addMessage("user", "I have a question.");
        List<ChatMessage> recentHistory = conversationMemory.getRecentHistory(2);
        assertNotNull(recentHistory);
        assertEquals(2, recentHistory.size());
    }

    @Test
    void testReset() {
        ConversationMemory conversationMemory = new ConversationMemory();
        conversationMemory.addMessage("user", "Hello, world!");
        conversationMemory.reset();
        assertEquals(0, conversationMemory.size());
    }
}