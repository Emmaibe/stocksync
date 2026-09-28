package com.lordibe.stocksync.platform.idempotency.web;

import com.lordibe.stocksync.platform.idempotency.service.IdempotencyOutcome;
import com.lordibe.stocksync.platform.idempotency.service.IdempotencyService;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Makes {@code Idempotency-Key} work for unsafe requests.
 *
 * <p>A mobile client on a poor connection cannot tell a request that never
 * arrived from a response that never came back. Retrying the first is necessary;
 * retrying the second double-counts stock. With a key, a retry returns the
 * original response instead of performing the work again.
 *
 * <p>Runs after authorisation, so an unauthenticated or forbidden request never
 * creates a record, and the tenant is already bound — the key is scoped per tenant
 * so two tenants can use the same key value.
 *
 * <p>Only successful responses are stored. A 4xx or 5xx releases the key, because
 * a client that fixes a bad request should be able to retry with the same key, and
 * a server error is exactly the case where a retry ought to be allowed to run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> GUARDED_METHODS =
            Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name());
    private static final int MAX_KEY_LENGTH = 120;

    private final IdempotencyService idempotency;
    private final ProblemWriter problems;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GUARDED_METHODS.contains(request.getMethod())
                || request.getHeader(HEADER) == null
                || TenantContext.current().isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key.length() > MAX_KEY_LENGTH) {
            problems.write(response, HttpStatus.BAD_REQUEST, "Invalid idempotency key",
                    HEADER + " must be at most " + MAX_KEY_LENGTH + " characters");
            return;
        }

        UUID tenantId = TenantContext.require();
        // Scoped by method and path so one key cannot collide across endpoints.
        String scope = request.getMethod() + " " + request.getRequestURI();
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());

        IdempotencyOutcome outcome = idempotency.begin(tenantId, scope, key, body);
        switch (outcome) {
            case IdempotencyOutcome.Replay replay -> {
                log.debug("Replaying stored response for idempotency key {} in {}", key, scope);
                writeReplay(response, replay);
                return;
            }
            case IdempotencyOutcome.BodyMismatch ignored -> {
                problems.write(response, HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused",
                        "This " + HEADER + " was already used for a different request body");
                return;
            }
            case IdempotencyOutcome.InFlight ignored -> {
                problems.write(response, HttpStatus.CONFLICT, "Request in progress",
                        "An identical request is still being processed; retry shortly");
                return;
            }
            case IdempotencyOutcome.Proceed ignored -> {
                // Fall through and run the request.
            }
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), captured);
            int status = captured.getStatus();
            if (status < 400) {
                idempotency.complete(tenantId, scope, key, status,
                        bodyOf(captured));
                stored = true;
            }
        } finally {
            if (!stored) {
                // Nothing worth replaying, and the client should be free to retry.
                idempotency.abandon(tenantId, scope, key);
            }
            captured.copyBodyToResponse();
        }
    }

    /** Null for an empty body, so a 204 is stored as SQL NULL rather than "". */
    private String bodyOf(ContentCachingResponseWrapper captured) {
        byte[] content = captured.getContentAsByteArray();
        return content.length == 0 ? null : new String(content, StandardCharsets.UTF_8);
    }

    private void writeReplay(HttpServletResponse response, IdempotencyOutcome.Replay replay)
            throws IOException {
        response.setStatus(replay.status());
        if (replay.body() == null) {
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(replay.body());
    }
}
