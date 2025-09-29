package ru.pachan.controller;//package ru.pachan.ws.controller;

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
import ru.pachan.service.ContactService;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@CrossOrigin
@RestController
@RequestMapping("/sse")
public class SseController {

    private final ContactService contactService;

    @PostMapping()
    public void contactMvc(@RequestParam String connectionId, @RequestParam String message) {
        contactService.sendContact(connectionId, "EVENT_NAME", message);
    }

    @GetMapping(value = "/{connectionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter contactSseMvc(@PathVariable String connectionId) {
        SseEmitter emitter = new SseEmitter(TimeUnit.HOURS.toMillis(12)); // TODO сделать переменную

        contactService.addSubscription(connectionId, emitter);

        emitter.onCompletion(() -> {
            log.info("onCompletion");
            contactService.removeSubscription(connectionId);
        });

        emitter.onTimeout(() -> {
            log.info("onTimeout");
            contactService.removeSubscription(connectionId);
        });

        emitter.onError(throwable -> {
            log.info("onError: {}", throwable.getMessage());
            contactService.removeSubscription(connectionId);
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("start")
                    .comment("SSE Connected"));
//                    .reconnectTime(5000L));
        } catch (IOException e) {
            log.error("Ошибка при отправке начального события", e);
        }

        return emitter;
    }

}
