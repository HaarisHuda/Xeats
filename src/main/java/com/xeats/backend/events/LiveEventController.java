package com.xeats.backend.events;

import jakarta.servlet.http.HttpSession;

import com.xeats.backend.identity.XeatsPrincipal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/events")
public class LiveEventController {
    private final AuthorizedLiveEventHub hub;

    public LiveEventController(AuthorizedLiveEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(Authentication authentication, HttpSession session) {
        XeatsPrincipal principal = (XeatsPrincipal) authentication.getPrincipal();
        SseEmitter emitter = hub.subscribe(principal, session);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
