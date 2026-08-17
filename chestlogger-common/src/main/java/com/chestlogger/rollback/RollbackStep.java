package com.chestlogger.rollback;

/**
 * Individual slot modification step in a rollback plan.
 */
public record RollbackStep(
        int slotIndex,
        String itemId,
        int targetDeltaQuantity,
        long metadataHash
) {
}
