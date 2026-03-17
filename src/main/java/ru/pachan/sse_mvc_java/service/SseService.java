package ru.pachan.sse_mvc_java.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.pachan.sse_mvc_java.dto.PendingEvent;

import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static ru.pachan.sse_mvc_java.util.SseEventName.HEARTBEAT;
import static ru.pachan.sse_mvc_java.util.SseEventName.SSE_MVC_JAVA;

@Slf4j
@Service
public class SseService {

//    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, ConcurrentLinkedDeque<SseEmitter>> connectionIdEmitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentLinkedQueue<PendingEvent>> connectionIdPendingEvents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> connectionIdLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService delayedSendPendingEvents = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-delayed-sender-pending-events");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
//        configureMetrics();
        configureHeartbeatScheduler();
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 30, 30, TimeUnit.SECONDS); // TODO переменные
    }

    @PreDestroy
    public void shutdown() {
        stopHeartbeatScheduler();
    }

    public void openConnection(String connectionId, SseEmitter emitter) {
        log.info("Открытие соединения");
        updateConnectionEmitter(connectionId, emitter);
        configureSse(connectionId, emitter);
        sendConnectEvent(connectionId, emitter);
        sendPendingEventsWithDelay(connectionId, emitter);
    }

    public void sendEvent(String connectionId, String eventName, Object data) {
        ConcurrentLinkedDeque<SseEmitter> emitters = connectionIdEmitters.get(connectionId);
        if (emitters != null && isEmitterActive(emitters.peekLast())) {
            SseEmitter emitter = emitters.peekLast();
            sendPendingEvents(connectionId, emitter);
            try {
                log.info("Отправляем");
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                log.info("Ошибка отправки, закрываем соединение");
                completeEmitter(emitter);
                addPendingEvent(connectionId, eventName, data);
            }
        } else {
            log.info("Не найдено соединение");
            addPendingEvent(connectionId, eventName, data);
        }
    }

    private void updateConnectionEmitter(String connectionId, SseEmitter emitter) {
        connectionIdEmitters.compute(connectionId, (id, emitters) -> {
            if (emitters == null) {
                emitters = new ConcurrentLinkedDeque<>();
            } else {
                SseEmitter oldEmitter = emitters.peekFirst();
                completeEmitter(oldEmitter);
            }
            emitters.addLast(emitter);
            return emitters;
        });
    }

    private void configureSse(String connectionId, SseEmitter emitter) {
        emitter.onCompletion(() -> {
            log.info("onCompletion для - {}", connectionId);
            deleteConnectionEmitter(connectionId);
        });

        emitter.onTimeout(() -> {
            log.info("onTimeout для - {}", connectionId);
            deleteConnectionEmitter(connectionId);
        });

        emitter.onError(throwable -> {
            log.info("onError для - {} | {}", connectionId, throwable.getMessage());
            deleteConnectionEmitter(connectionId);
        });
    }

    private void sendConnectEvent(String connectionId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(SSE_MVC_JAVA.getEventName()).comment("SSE connected"));
        } catch (Exception e) {
            log.error("Ошибка при отправке начального события для - {}", connectionId, e);
        }
    }

    private void sendPendingEventsWithDelay(String connectionId, SseEmitter emitter) {
        delayedSendPendingEvents.schedule(() -> sendPendingEvents(connectionId, emitter), 500, TimeUnit.MILLISECONDS);
    }

    private void deleteConnectionEmitter(String connectionId) {
        connectionIdEmitters.computeIfPresent(connectionId, (id, emitters) -> {
            emitters.pollFirst();
            return emitters.isEmpty() ? null : emitters;
        });
    }

    @Async
    @Scheduled(initialDelay = 300000, fixedDelay = 300000)
    protected void cleanupExpiredEventsAndLocks() {
        connectionIdPendingEvents.forEach((connectionId, events) -> {
            if (isConnectionActive(connectionId)) {
                return;
            }
            connectionIdLocks.remove(connectionId);
            events.removeIf(event -> event.isExpired(Duration.ofMinutes(1)));
        });

        cleanMapWithEmptyPendingEvents();
    }

    private boolean isConnectionActive(String connectionId) {
        return connectionIdEmitters.get(connectionId) != null
               && isEmitterActive(connectionIdEmitters.get(connectionId).peekLast());
    }

    private void cleanMapWithEmptyPendingEvents() {
        connectionIdPendingEvents.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private void addPendingEvent(String agentId, String eventName, Object data) {
        connectionIdPendingEvents.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>())
                .offer(new PendingEvent(eventName, data, System.nanoTime()));
    }

    private void sendPendingEvents(@NonNull String connectionId, SseEmitter emitter) {
        if (isPendingEventsEmpty(connectionId)) return;
        ReentrantLock lock = connectionIdLocks.computeIfAbsent(connectionId, k -> new ReentrantLock());
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    connectionIdPendingEvents.computeIfPresent(connectionId, (id, events) -> {
                        if (CollectionUtils.isEmpty(events)) return null;
                        Iterator<PendingEvent> iterator = events.iterator();
                        while (iterator.hasNext()) {
                            PendingEvent event = iterator.next();
                            if (event.isExpired(Duration.ofMinutes(1))) {
                                iterator.remove();
                            } else if (isEmitterActive(emitter)) {
                                try {
                                    emitter.send(SseEmitter.event().name(event.eventName()).data(event.data()));
                                    iterator.remove();
                                } catch (Exception e) {
                                    log.info("Ошибка отправки отложенного сообщения");
                                }
                            }
                        }
                        return events.isEmpty() ? null : events;
                    });
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isPendingEventsEmpty(String connectionId) {
        ConcurrentLinkedQueue<PendingEvent> events = connectionIdPendingEvents.get(connectionId);
        return events == null || events.isEmpty();
    }

    private void sendHeartbeats() {
        try {
            connectionIdEmitters.forEach((connectionId, emitters) -> {
                SseEmitter emitter = emitters.peekLast();
                if (isEmitterActive(emitter)) {
                    try {
                        emitter.send(SseEmitter.event().name(HEARTBEAT.getEventName()).data("ping"));
                    } catch (IOException e) {
                        log.info("Heartbeat неудачен для - {}", connectionId);
                        completeEmitter(emitter);
                    }
                }

            });
        } catch (Exception e) {
            log.error("Неизвестная ошибка в sendHeartbeats", e);
        }
    }

    private boolean isEmitterActive(SseEmitter emitter) {
        return emitter != null;
    }

    private void completeEmitter(SseEmitter emitter) {
        if (isEmitterActive(emitter)) {
            emitter.complete();
        }
    }

//    private void configureMetrics() {
//        Gauge.builder("sse.subscriptions.active.count", agentIdEmitters, ConcurrentMap::size)
//                .description("Number of active subscriptions")
//                .register(meterRegistry);
//
//        Gauge.builder("sse.subscriptions.emitters.total.count",
//                        agentIdEmitters,
//                        map -> map.values().stream().mapToInt(ConcurrentLinkedDeque::size).sum())
//                .description("Total number of active SseEmitter instances across all subscriptions")
//                .register(meterRegistry);
//
//        Gauge.builder("pending.events.active.count", agentIdPendingEvents, ConcurrentMap::size)
//                .description("Number of active pending events queues")
//                .register(meterRegistry);
//
//        Gauge.builder("pending.events.total.count",
//                        agentIdPendingEvents,
//                        map -> map.values().stream().mapToInt(ConcurrentLinkedQueue::size).sum())
//                .description("Total number of pending events across all event queues")
//                .register(meterRegistry);
//    }

    private void configureHeartbeatScheduler() {
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                5,
                5,
                TimeUnit.SECONDS
        );
    }

    private void stopHeartbeatScheduler() {
        heartbeatScheduler.shutdown();
        try {
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}