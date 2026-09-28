package com.lordibe.stocksync.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an RFC 9457 problem body from inside the security filter chain.
 *
 * <p>Security failures happen before any controller runs, so
 * {@code @RestControllerAdvice} never sees them — which is why an unhandled 401
 * arrives as an empty response. This renders the same problem shape by hand,
 * using the application's own {@link JsonMapper} so the JSON matches what
 * controllers produce.
 */
@Component
@RequiredArgsConstructor
public class ProblemWriter {

    private final JsonMapper jsonMapper;

    public void write(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }
}
