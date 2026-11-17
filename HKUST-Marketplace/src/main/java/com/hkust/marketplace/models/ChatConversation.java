package com.hkust.marketplace.models;

import java.util.List;

public record ChatConversation(ChatThread thread, List<ChatMessage> messages, boolean canSend) {
    public ChatConversation { messages = List.copyOf(messages); }
}
