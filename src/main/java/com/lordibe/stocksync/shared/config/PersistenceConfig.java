package com.lordibe.stocksync.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the order of the transaction advisor.
 *
 * <p>This matters: the tenant filter has to be enabled on the Hibernate session
 * that the transaction already opened, so the filter advisor must sit *inside*
 * the transaction advice. Spring's default transaction order is
 * {@code LOWEST_PRECEDENCE}, which would make it innermost and leave our advisor
 * running before any session exists. Fixing both orders explicitly removes the
 * ambiguity.
 *
 * @see com.lordibe.stocksync.shared.tenancy.TenantFilterAdvisorConfig
 */
@Configuration
@EnableTransactionManagement(order = PersistenceConfig.TRANSACTION_ADVISOR_ORDER)
public class PersistenceConfig {

    /** Outer. Opens the transaction and with it the Hibernate session. */
    public static final int TRANSACTION_ADVISOR_ORDER = 100;

    /** Inner. Runs once the session exists, so the filter lands on it. */
    public static final int TENANT_FILTER_ADVISOR_ORDER = 200;
}
