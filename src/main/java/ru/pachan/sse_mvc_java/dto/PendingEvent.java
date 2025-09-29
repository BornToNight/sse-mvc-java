package ru.pachan.sse_mvc_java.dto;

import java.time.Duration;
import java.time.Instant;

public record PendingEvent(
        String eventName,
        Object data,
        Instant timestamp
) {

    public boolean isExpired(int eventTimeLiveMinutes) {
        return Duration.between(timestamp, Instant.now()).compareTo(Duration.ofMinutes(eventTimeLiveMinutes)) > 0;
    }

}
