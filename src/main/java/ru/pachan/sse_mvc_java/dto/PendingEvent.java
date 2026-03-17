package ru.pachan.sse_mvc_java.dto;

import java.time.Duration;

public record PendingEvent(
        String eventName,
        Object data,
        long createdAtNanos
) {

    public boolean isExpired(Duration eventQueueTimeLive) {
        long now = System.nanoTime();
        long expirationNanos = eventQueueTimeLive.toNanos();
        return now - createdAtNanos > expirationNanos;
    }

}
