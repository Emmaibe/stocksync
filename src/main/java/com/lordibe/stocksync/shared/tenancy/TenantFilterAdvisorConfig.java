package com.lordibe.stocksync.shared.tenancy;

import com.lordibe.stocksync.shared.config.PersistenceConfig;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enables the Hibernate tenant filter on every transactional unit of work in this
 * application, so a service can never forget to.
 *
 * <p>Built from plain Spring AOP rather than an {@code @Aspect}: this needs only
 * the proxying already on the classpath, no AspectJ weaver, and the advisor's
 * order can be set directly — which matters, because the filter has to be applied
 * to the Hibernate session the transaction already opened. Hence
 * {@link PersistenceConfig#TENANT_FILTER_ADVISOR_ORDER} sitting inside
 * {@link PersistenceConfig#TRANSACTION_ADVISOR_ORDER}.
 *
 * <p><strong>Why the activator is resolved lazily.</strong> The auto-proxy creator
 * has to know every {@link Advisor} bean before it can proxy anything, so advisors
 * are instantiated during {@code BeanPostProcessor} registration — long before the
 * {@code EntityManagerFactory} exists. Taking {@link TenantFilterActivator}
 * directly as a parameter therefore drags JPA into that early phase and fails the
 * context with "no qualifying bean of type EntityManager". An
 * {@link ObjectProvider} defers the lookup to the first intercepted call, by which
 * time JPA is up; {@link SingletonSupplier} then caches it. The
 * {@code ROLE_INFRASTRUCTURE} marking and {@code proxyBeanMethods = false} keep
 * this configuration out of the auto-proxying it helps set up.
 *
 * <p>Matched: any {@code @Transactional} method, or any method of a
 * {@code @Transactional} class, on a bean in this project's own packages.
 * Spring Data's repository implementation is not one of those, so a bare
 * repository call outside a transactional service does not get the filter — which
 * is why repository queries still take {@code tenantId} explicitly. The filter is
 * the net; those parameters are the intent.
 *
 * <p>When no tenant is bound the filter is left off rather than the call refused:
 * creating a tenant, resolving a login by slug and platform maintenance are all
 * legitimately tenant-less. Services that must have a tenant call
 * {@link TenantContext#require()}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class TenantFilterAdvisorConfig {

    private static final String APPLICATION_PACKAGE = "com.lordibe.stocksync";

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    Advisor tenantFilterAdvisor(ObjectProvider<TenantFilterActivator> activatorProvider) {
        Pointcut transactional = new ComposablePointcut(
                new AnnotationMatchingPointcut(Transactional.class, true))
                .union(AnnotationMatchingPointcut.forMethodAnnotation(Transactional.class))
                .intersection((ClassFilter) type -> type.getName().startsWith(APPLICATION_PACKAGE));

        Supplier<TenantFilterActivator> activator = SingletonSupplier.of(activatorProvider::getObject);

        MethodInterceptor interceptor = invocation -> {
            UUID tenantId = TenantContext.current().orElse(null);
            if (tenantId != null) {
                // Idempotent: an inner call just re-sets the same parameter.
                activator.get().activateFor(tenantId);
            } else if (log.isTraceEnabled()) {
                log.trace("No tenant bound for {}; tenant filter left off",
                        invocation.getMethod().getName());
            }
            return invocation.proceed();
        };

        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(transactional, interceptor);
        advisor.setOrder(PersistenceConfig.TENANT_FILTER_ADVISOR_ORDER);
        return advisor;
    }
}
