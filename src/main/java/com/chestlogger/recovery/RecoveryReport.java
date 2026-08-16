package com.chestlogger.recovery;

import java.util.List;

/**
 * Diagnostic report produced during startup tail recovery.
 */
public record RecoveryReport(
        boolean hasCorruptions,
        long totalTruncatedBytes,
        long maxSequenceId,
        int activeSegmentIndex,
        List<String> issues
) {
    public RecoveryReport {
        issues = List.copyOf(issues);
    }
}
