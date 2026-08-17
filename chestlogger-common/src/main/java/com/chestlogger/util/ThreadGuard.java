package com.chestlogger.util;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Thread guard to enforce zero main-thread disk blocking and background worker boundaries.
 */
public final class ThreadGuard {
    private static volatile BooleanSupplier serverThreadChecker = () -> false;

    private ThreadGuard() {}

    /**
     * Registers a supplier that returns true if the current thread is the main server thread.
     */
    public static void setServerThreadChecker(BooleanSupplier checker) {
        serverThreadChecker = Objects.requireNonNull(checker, "checker cannot be null");
    }

    /**
     * Resets the thread checker to default (false).
     */
    public static void reset() {
        serverThreadChecker = () -> false;
    }

    /**
     * Asserts that the current thread is NOT a main server thread.
     * Throws IllegalStateException if called on the main server thread.
     */
    public static void assertNotServerThread(String operation) {
        if (serverThreadChecker.getAsBoolean()) {
            throw new IllegalStateException("CRITICAL THREAD SAFETY VIOLATION: " + operation + " invoked on main server thread!");
        }
    }
}
