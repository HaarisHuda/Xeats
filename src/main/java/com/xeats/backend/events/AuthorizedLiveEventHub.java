package com.xeats.backend.events;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpSession;

import com.xeats.backend.identity.IdentityRepository;
import com.xeats.backend.identity.UserRole;
import com.xeats.backend.identity.XeatsPrincipal;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Single-instance, in-memory SSE fanout with current-session and audience checks for every delivery. */
@Component
public class AuthorizedLiveEventHub {
    private static final long HEARTBEAT_SECONDS = 5;
    private static final long RETRY_MILLIS = 3000;

    private final IdentityRepository identities;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();

    public AuthorizedLiveEventHub(IdentityRepository identities, ScheduledExecutorService liveEventScheduler) {
        this.identities = identities;
        this.scheduler = liveEventScheduler;
    }

    public SseEmitter subscribe(XeatsPrincipal principal, HttpSession session) {
        SseEmitter emitter = new SseEmitter(0L);
        Connection connection = new Connection(UUID.randomUUID(), principal.userId(), session, emitter);
        connections.put(connection.id, connection);
        emitter.onCompletion(() -> close(connection, false));
        emitter.onTimeout(() -> close(connection, true));
        emitter.onError(ignored -> close(connection, false));
        connection.heartbeat = scheduler.scheduleAtFixedRate(
                () -> heartbeat(connection), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            emitter.send(SseEmitter.event().reconnectTime(RETRY_MILLIS).comment("connected"));
        } catch (IOException | IllegalStateException exception) {
            close(connection, true);
        }
        return emitter;
    }

    void publish(PendingLiveEvent event) {
        for (Connection connection : connections.values()) {
            if (connection.closed.get()) {
                continue;
            }
            try {
                if (!isCurrentSession(connection)) {
                    close(connection, true);
                    continue;
                }
                if (!isAuthorizedAudience(connection.userId, event)) {
                    continue;
                }
                connection.emitter.send(SseEmitter.event()
                        .id(event.id().toString())
                        .name(event.type().name())
                        .data(event.envelope(), MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException exception) {
                close(connection, true);
            }
        }
    }

    private void heartbeat(Connection connection) {
        if (connection.closed.get()) {
            return;
        }
        if (!isCurrentSession(connection)) {
            close(connection, true);
            return;
        }
        try {
            connection.emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException exception) {
            close(connection, true);
        }
    }

    private boolean isCurrentSession(Connection connection) {
        try {
            Object stored = connection.session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context)) {
                return false;
            }
            Authentication authentication = context.getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof XeatsPrincipal principal)
                    || !connection.userId.equals(principal.userId()) || !identities.isEnabled(connection.userId)) {
                return false;
            }
            return identities.hasAnyActiveRole(connection.userId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isAuthorizedAudience(UUID userId, PendingLiveEvent event) {
        if (identities.hasActiveRole(userId, UserRole.DEMO_ADMIN)) {
            return true;
        }
        if (event.type() == LiveEventType.ORDER_STATUS_CHANGED) {
            return userId.equals(event.employeeId()) && identities.hasActiveRole(userId, UserRole.EMPLOYEE)
                    || identities.hasCounterAssignment(userId, event.counterId());
        }
        return identities.hasActiveRole(userId, UserRole.EMPLOYEE)
                || identities.hasCounterAssignment(userId, event.counterId());
    }

    private void close(Connection connection, boolean completeEmitter) {
        if (!connection.closed.compareAndSet(false, true)) {
            return;
        }
        connections.remove(connection.id, connection);
        ScheduledFuture<?> heartbeat = connection.heartbeat;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (completeEmitter) {
            connection.emitter.complete();
        }
    }

    private static final class Connection {
        private final UUID id;
        private final UUID userId;
        private final HttpSession session;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> heartbeat;

        private Connection(UUID id, UUID userId, HttpSession session, SseEmitter emitter) {
            this.id = id;
            this.userId = userId;
            this.session = session;
            this.emitter = emitter;
        }
    }
}
