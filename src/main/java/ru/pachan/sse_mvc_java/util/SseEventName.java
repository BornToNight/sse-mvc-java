package ru.pachan.sse_mvc_java.util;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum SseEventName {

    HEARTBEAT("heartbeat"),
    SSE_MVC_JAVA("sseMvcJava");

    private final String eventName;
}
