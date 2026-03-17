package ru.pachan.sse_mvc_java.controller;//package ru.pachan.ws.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.pachan.sse_mvc_java.service.SseService;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
@CrossOrigin
@RestController
@RequestMapping("/sse")
public class SseController {

    public static final Duration DURATION = Duration.ofHours(12);
    private final SseService sseService;

    @PostMapping()
    public void contactMvc(@RequestParam String connectionId, @RequestParam String message) {
        sseService.sendEvent(connectionId, "EVENT_NAME", message);
    }

    @GetMapping(value = "/{connectionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter contactSseMvc(@PathVariable String connectionId) {
        SseEmitter emitter = new SseEmitter(DURATION.toMillis());
        sseService.openConnection(connectionId, emitter);
        return emitter;
    }

}
