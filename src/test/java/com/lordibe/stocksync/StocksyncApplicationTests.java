package com.lordibe.stocksync;

import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;

class StocksyncApplicationTests extends AbstractPostgresIntegrationTest {

    /** Boots the full context and runs Flyway, so the migration is checked too. */
    @Test
    void contextLoads() {
    }
}
