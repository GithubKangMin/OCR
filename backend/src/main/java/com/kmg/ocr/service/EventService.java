package com.kmg.ocr.service;

import com.kmg.ocr.dto.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));

        return emitter;
    }

    public void publish(String type, String jobId, String message, Object payload) {
        EventMessage event = new EventMessage(type, jobId, message, OffsetDateTime.now().toString(), payload);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(type).data(event));
            } catch (IOException e) {
                log.debug("Removing SSE emitter after send failure: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
