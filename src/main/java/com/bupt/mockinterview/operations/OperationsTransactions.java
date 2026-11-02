package com.bupt.mockinterview.operations;

import com.bupt.mockinterview.security.TenantContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Outer transaction binds OSIV first; inner new Session resolves the requested tenant/root. */
@Component
public class OperationsTransactions {
    private final TransactionTemplate outer, inner;

    public OperationsTransactions(PlatformTransactionManager manager) {
        outer = new TransactionTemplate(manager);
        inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T tenant(Long userId, Supplier<T> work) {
        if (TenantContext.isRoot())
            throw new IllegalStateException("tenant writes cannot originate in root context");
        return outer.execute(
                s -> {
                    Long previous = TenantContext.getTenantId();
                    boolean root = TenantContext.isRoot();
                    try {
                        TenantContext.set(userId);
                        return inner.execute(x -> work.get());
                    } finally {
                        TenantContext.set(previous);
                    }
                });
    }

    public <T> T root(Supplier<T> work) {
        return outer.execute(
                s -> {
                    Object[] value = new Object[1];
                    TenantContext.runAsRoot(() -> value[0] = inner.execute(x -> work.get()));
                    @SuppressWarnings("unchecked")
                    T result = (T) value[0];
                    return result;
                });
    }
}
