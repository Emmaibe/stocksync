package com.lordibe.stocksync.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real schema.
 *
 * <p>A real PostgreSQL, not H2: the things worth testing here — partial unique
 * indexes, composite foreign keys, JSONB, {@code SELECT … FOR UPDATE} — either
 * behave differently or do not exist on an in-memory substitute. Flyway builds the
 * schema, so the migration is exercised on every run.
 *
 * <p><strong>Singleton container, started by hand.</strong> Deliberately not
 * {@code @Testcontainers} with {@code @Container}: that ties the container's life
 * to the test <em>class</em> and stops it when the class finishes, while Spring
 * caches the application context and hands it to the next test class — which then
 * finds a dead database ("connection refused") on its first query. Starting it once
 * in a static initialiser and never stopping it matches the cached context's
 * lifetime; Testcontainers' own reaper removes it when the JVM exits.
 */
@SpringBootTest(properties = {
        // The app refuses to start without a signing key; tests supply a throwaway
        // one rather than depending on the developer's environment.
        "stocksync.security.jwt.secret=test-only-signing-key-at-least-32-bytes-long"
})
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
