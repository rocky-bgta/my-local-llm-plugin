package plugin.memory;

import plugin.llm.model.ChatMessage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConversationMemoryTest {
    @Test
    public void testAddMessage() {
        ConversationMemory conversationMemory = new ConversationMemory();
        
        conversationMemory.addMessage("user", "Hello, world!");
        assertEquals(1, conversationMemory.size());
        
        conversationMemory.addMessage("assistant", "How can I assist you today?");
        assertEquals(2, conversationMemory.size());
    }

    @Test
    public void testAddUserMessage() {
        ConversationMemory conversationMemory = new ConversationMemory();
        
        conversationMemory.addUserMessage("Hello, world!");
        assertEquals(1, conversationMemory.size());
        
        conversationMemory.addAssistantMessage("How can I assist you today?");
        assertEquals(2, conversationMemory.size());
    }

    @Test
    public void testAddAssistantMessage() {
        ConversationMemory conversationMemory = new ConversationMemory();
        
        conversationMemory.addAssistantMessage("Hello, world!");
        assertEquals(1, conversationMemory.size());
        
        conversationMemory.addUserMessage("How can I assist you today?");
        assertEquals(2, conversationMemory.size());
    }

    @Test
    public void testGetHistory() {
        ConversationMemory conversationMemory = new ConversationMemory();
        
        conversationMemory.addMessage("user", "Hello, world!");
        conversationMemory.addMessage("assistant", "How can I assist you today?");
        
        List<ChatMessage> expectedHistory = new ArrayList<>();
        expectedHistory.add(new ChatMessage("user", "Hello, world!"));
        expectedHistory.add(new ChatMessage("assistant", "How can I assist you today?"));
        
        assertEquals(expectedHistory, conversationMemory.getHistory());
    }

    @Test
    public void testGetRecentHistory() {
        ConversationMemory conversationMemory = new ConversationMemory();
        
        conversationMemory.addMessage("user", "Hello, world!");
        conversationMemory.addMessage("assistant", "How can I assist you today?");
        
        List<ChatMessage> expectedRecentHistory = new ArrayList<>();
        expectedRecentHistory.add(new ChatMessage("assistant", "How can I assist you today?"));
        
        assertEquals(expectedRecentHistory, conversationMemory.getRecentHistory(1));
    }

    @Test
    public void testReset() {
        ConversationMemory conversationMemory = new ConversationMemory();
        
        conversationMemory.addMessage("user", "Hello, world!");
        conversationMemory.addMessage("assistant", "How can I assist you today?");
        
        assertEquals(2, conversationMemory.size());
        
        conversationMemory.reset();
        
        assertEquals(0, conversationMemory.size());
    }
}
