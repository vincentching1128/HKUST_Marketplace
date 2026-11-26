package com.hkust.marketplace.models;

import java.util.List;

/**
 * One chat thread with its ordered messages and whether the current viewer may send.
 *
 * @param thread the listing-linked conversation metadata
 * @param messages immutable ordered message history
 * @param canSend whether the viewer can post; a deleted counterpart makes this false
 */
public record ChatConversation(ChatThread thread, List<ChatMessage> messages, boolean canSend) {
    public ChatConversation { messages = List.copyOf(messages); }
}
