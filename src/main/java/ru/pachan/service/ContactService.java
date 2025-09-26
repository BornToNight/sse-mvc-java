package ru.pachan.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.pachan.dto.PendingEvent;
import ru.pachan.util.ResponseException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ContactService {

//    @Value("${app.sse.heartbeat-interval:30}")
//    private long heartbeatInterval;
//
//    @Value("${app.sse.emitter-timeout:30}")
//    private long emitterTimeout;

    private final ConcurrentMap<String, Deque<SseEmitter>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Queue<PendingEvent>> pendingEvents = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {

// TODO логи


        // Один общий heartbeat для всех клиентов
//        heartbeatScheduler.scheduleAtFixedRate(
//                this::sendHeartbeats,
//                sseConfig.getHeartbeatInterval().toSeconds(),
//                sseConfig.getHeartbeatInterval().toSeconds(),
//                TimeUnit.SECONDS
//        );
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 30, 30, TimeUnit.SECONDS); // TODO переменные
    }

    public void addSubscription(String agentId, SseEmitter emitter) {
        log.info("Открытие соединения");
        Deque<SseEmitter> emitters = subscriptions.computeIfAbsent(agentId,
                k -> new ConcurrentLinkedDeque<>());

        SseEmitter oldEmitter = emitters.peekFirst();
        if (isEmitterActive(oldEmitter)) {
            completeEmitter(oldEmitter);
        }

        emitters.addLast(emitter);

        sendPendingEvents(agentId, emitter);
    }

    public void removeSubscription(String agentId) {
        log.info("Разрыв соединения");
        Deque<SseEmitter> emitters = subscriptions.get(agentId);
        if (emitters != null) {
            emitters.pollFirst();
            if (emitters.isEmpty()) {
                subscriptions.remove(agentId);
            }
        }

//        SseEmitter emitter = subscriptions.remove(agentId);
//
//        // Убираем цикличность
//        if (isEmitterActive(emitter)) {
//            completeEmitter(emitter);
//        }
    }

    public void sendContact(String agentId, String eventName, Object data) {
        Deque<SseEmitter> emitters = subscriptions.get(agentId);
        if (emitters != null && isEmitterActive(emitters.peekLast())) {
            SseEmitter emitter = emitters.peekLast();
            try {
                log.info("Отправляем");
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                        .name(eventName)
                        .data(data);
                emitter.send(event);
            } catch (Exception e) {
                log.info("Ошибка отправки, закрываем соединение");
                completeEmitter(emitter);
                addPendingEvent(agentId, eventName, data, Instant.now());
            }
        } else {
            log.info("Не найдено соединение");
            addPendingEvent(agentId, eventName, data, Instant.now());
            ResponseException.notFound("Не найдено открытое соединение");
        }
    }

    private void sendHeartbeats() {
        subscriptions.forEach((agentId, emitters) -> {

            SseEmitter emitter = emitters.peekLast();
            if (isEmitterActive(emitter)) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                } catch (IOException e) {
                    log.info("Heartbeat неудачен");
                    completeEmitter(emitter);
                }
            }

        });
    }

    @Async
    @Scheduled(initialDelay = 300000, fixedDelay = 300000)
    protected void cleanupExpiredEvents() {
        log.info("cleanup, before - {}", pendingEvents.size());
        pendingEvents.forEach((key, value) -> log.info("size IN - {}; size - {}", key, value.size()));

        pendingEvents.forEach((agentId, events) -> {
            // Если соединение активно, не удаляем события для этого агента
            if (isEmitterActive(subscriptions.get(agentId).peekLast())) {
                return;
            }

            events.removeIf(event -> event.isExpired(1));
        });

        log.info("cleanup, after - {}", pendingEvents.size());
        pendingEvents.forEach((key, value) -> log.info("size IN - {}; size - {}", key, value.size()));
    }

    private boolean isEmitterActive(SseEmitter emitter) {
        return emitter != null;
    }

    private void completeEmitter(SseEmitter emitter) {
        emitter.complete();
    }


    private void sendPendingEvents(String agentId, SseEmitter emitter) {
        Queue<PendingEvent> events = pendingEvents.get(agentId);
        List<PendingEvent> eventsToRemove = new ArrayList<>();
        if (events != null && !events.isEmpty()) {
            for (PendingEvent event : events) {
                if (event.isExpired(1)) {
                    eventsToRemove.add(event);
                } else if (isEmitterActive(emitter)) {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.eventName())
                                .data(event.data()));
                        eventsToRemove.add(event);
                    } catch (IOException e) {
                        log.info("Ошибка отправки отложенного события");
                        break;
                    } catch (Exception e) {
                        log.info("EXCEPTIOOOOOOOON");
                    }
                }
            }
            events.removeAll(eventsToRemove);
            if (events.isEmpty()) {
                pendingEvents.remove(agentId);
            }
        }
    }

    private void addPendingEvent(String agentId, String eventName, Object data, Instant instant) {
        log.info("addPendingEvent, before - {}", pendingEvents.size());
        pendingEvents.forEach((key, value) -> log.info("size IN - {}; size - {}", key, value.size()));

        pendingEvents.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>())
                .offer(new PendingEvent(eventName, data, instant));

        log.info("addPendingEvent, after - {}", pendingEvents.size());
        pendingEvents.forEach((key, value) -> log.info("size IN - {}; size - {}", key, value.size()));
    }
}