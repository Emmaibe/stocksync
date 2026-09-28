package com.lordibe.stocksync.shared.security;

import com.lordibe.stocksync.shared.web.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers an unauthenticated request with a problem body instead of an empty 401.
 *
 * <p>A mobile client needs to tell "no token / expired token" (refresh and retry)
 * from "insufficient permission" (do not retry). An empty body makes those
 * indistinguishable apart from the status code.
 *
 * <p>The detail stays generic: it never says whether the token was missing,
 * malformed, expired or signed with the wrong key, since that only helps someone
 * probing.
 */
@Component
@RequiredArgsConstructor
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemWriter problems;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        problems.write(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "A valid access token is required for this endpoint");
    }
}
