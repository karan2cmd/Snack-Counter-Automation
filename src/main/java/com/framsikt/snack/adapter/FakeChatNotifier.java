package com.framsikt.snack.adapter;

import com.framsikt.snack.port.ChatNotifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Simulated chat tool: records every message so tests and the demo can inspect them. */
public final class FakeChatNotifier implements ChatNotifier {

    public record Message(String recipient, boolean channel, String text) {
        @Override
        public String toString() {
            return (channel ? "#snacks-" : "@") + recipient + ": " + text;
        }
    }

    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final boolean echo;

    public FakeChatNotifier() {
        this(false);
    }

    /** @param echo also print every message to stdout (used by the demo) */
    public FakeChatNotifier(boolean echo) {
        this.echo = echo;
    }

    @Override
    public void sendDirect(String employeeId, String message) {
        record(new Message(employeeId, false, message));
    }

    @Override
    public void postToOffice(String officeId, String message) {
        record(new Message(officeId, true, message));
    }

    private void record(Message m) {
        messages.add(m);
        if (echo) {
            System.out.println("  [chat] " + m);
        }
    }

    public List<Message> all() {
        return List.copyOf(messages);
    }

    public List<String> directMessagesTo(String employeeId) {
        return messages.stream().filter(m -> !m.channel() && m.recipient().equals(employeeId))
                .map(Message::text).toList();
    }

    public List<String> channelMessages(String officeId) {
        return messages.stream().filter(m -> m.channel() && m.recipient().equals(officeId))
                .map(Message::text).toList();
    }
}
