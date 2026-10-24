package com.bupt.mockinterview.agent;

import java.util.function.Consumer;

/** Streams visible prose immediately while withholding a possibly split memory footer. */
final class BuddyReplyFilter implements Consumer<String> {
    static final String MARKER = "<buddy_memory>";
    private final Consumer<String> output;
    private final StringBuilder pending = new StringBuilder();
    private boolean footer;

    BuddyReplyFilter(Consumer<String> output) { this.output = output; }

    @Override
    public void accept(String delta) {
        if (footer || delta == null || delta.isEmpty()) return;
        pending.append(delta);
        int marker = pending.indexOf(MARKER);
        if (marker >= 0) {
            emit(marker);
            pending.setLength(0);
            footer = true;
            return;
        }
        int held = Math.min(MARKER.length() - 1, pending.length());
        while (held > 0 && !MARKER.startsWith(pending.substring(pending.length() - held))) held--;
        emit(pending.length() - held);
    }

    void finish() {
        // A partial marker at EOF belongs to a truncated footer, never visible prose.
        if (!footer && !pending.isEmpty() && !MARKER.startsWith(pending.toString())) emit(pending.length());
        pending.setLength(0);
    }

    private void emit(int length) {
        if (length > 0) {
            String text = pending.substring(0, length);
            pending.delete(0, length);
            output.accept(text);
        }
    }
}
