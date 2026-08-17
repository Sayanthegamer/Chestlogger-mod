package com.chestlogger.rollback;

import java.util.List;

/**
 * Proposed rollback execution plan with conflict metrics.
 */
public record RollbackPlan(
        List<RollbackStep> steps,
        int conflictCount
) {
    public RollbackPlan {
        steps = List.copyOf(steps);
    }

    public boolean hasConflicts() {
        return conflictCount > 0;
    }
}
