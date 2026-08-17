package com.chestlogger.rollback;

/**
 * Result of executing a rollback plan.
 */
public record RollbackResult(
        int appliedSteps,
        int conflictCount,
        boolean success
) {
}
