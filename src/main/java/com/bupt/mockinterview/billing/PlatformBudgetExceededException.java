package com.bupt.mockinterview.billing;

/** Quota subtype deliberately crosses existing noRollback/fallback boundaries unchanged. */
public class PlatformBudgetExceededException extends QuotaExhaustedException {
    public PlatformBudgetExceededException(String message) { super(message); }
}
