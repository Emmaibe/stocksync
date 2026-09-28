package com.lordibe.stocksync.shared.security;

import com.lordibe.stocksync.shared.web.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers an authenticated-but-unauthorised request with a problem body.
 *
 * <p>403, not 404: the caller is who they say they are and simply lacks the
 * permission. The response does not name the missing permission code — that would
 * let a caller map the permission model by probing.
 */
@Component
@RequiredArgsConstructor
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemWriter problems;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        problems.write(response, HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to do that");
    }
}
