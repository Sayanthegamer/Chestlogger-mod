package com.chestlogger.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe manager for player trust relationships with persistent JSON storage.
 * Trusted players are exempt from theft and raid security alerts when accessing an owner's containers.
 * <p>
 * <b>Directional Semantics:</b> Trust is strictly directional (asymmetric). If Alice grants trust to Bob
 * ({@code trust(Alice, Bob)}), Bob is permitted to access Alice's containers without security alerts,
 * but Alice is <i>not</i> automatically permitted to access Bob's containers unless Bob explicitly grants
 * trust back to Alice ({@code trust(Bob, Alice)}).
 */
public final class TrustManager {

    private static final Logger LOGGER = Logger.getLogger("ChestLogger-TrustManager");
    private static final String DEFAULT_FILE_NAME = "trust_data.json";

    private final Path storagePath;
    private final ConcurrentHashMap<UUID, Set<UUID>> trustMap;
    private final ReentrantLock ioLock;

    /**
     * Constructs a TrustManager storing data at the default path ("trust_data.json").
     */
    public TrustManager() {
        this(Path.of(DEFAULT_FILE_NAME));
    }

    /**
     * Constructs a TrustManager storing data at the specified path.
     * If the path is a directory, it resolves "trust_data.json" within it.
     *
     * @param storagePath Storage file or directory path.
     */
    public TrustManager(Path storagePath) {
        Objects.requireNonNull(storagePath, "storagePath cannot be null");
        if (Files.isDirectory(storagePath) || storagePath.toString().endsWith("/") || storagePath.toString().endsWith("\\")) {
            this.storagePath = storagePath.resolve(DEFAULT_FILE_NAME);
        } else {
            this.storagePath = storagePath;
        }
        this.trustMap = new ConcurrentHashMap<>();
        this.ioLock = new ReentrantLock();
    }

    /**
     * Adds trust from an owner to a trusted player.
     *
     * @param ownerUuid UUID of the container owner granting trust.
     * @param trustedUuid UUID of the player being trusted.
     * @return true if trust was newly granted, false if already trusted or invalid.
     */
    public boolean trust(UUID ownerUuid, UUID trustedUuid) {
        if (ownerUuid == null || trustedUuid == null || ownerUuid.equals(trustedUuid)) {
            return false;
        }
        return trustMap.computeIfAbsent(ownerUuid, k -> ConcurrentHashMap.newKeySet()).add(trustedUuid);
    }

    /**
     * Removes trust between an owner and a player.
     *
     * @param ownerUuid UUID of the container owner revoking trust.
     * @param untrustedUuid UUID of the player being untrusted.
     * @return true if trust was revoked, false if not found or invalid.
     */
    public boolean untrust(UUID ownerUuid, UUID untrustedUuid) {
        if (ownerUuid == null || untrustedUuid == null) {
            return false;
        }
        Set<UUID> set = trustMap.get(ownerUuid);
        if (set == null) {
            return false;
        }
        boolean removed = set.remove(untrustedUuid);
        if (set.isEmpty()) {
            trustMap.remove(ownerUuid, Set.of());
        }
        return removed;
    }

    /**
     * Checks if an actor is trusted by the container owner.
     * A player always trusts themselves.
     *
     * @param ownerUuid UUID of the container owner.
     * @param potentialTrustedUuid UUID of the player performing the interaction.
     * @return true if actor is trusted or is the owner themselves, false otherwise.
     */
    public boolean isTrusted(UUID ownerUuid, UUID potentialTrustedUuid) {
        if (ownerUuid == null || potentialTrustedUuid == null) {
            return false;
        }
        if (ownerUuid.equals(potentialTrustedUuid)) {
            return true;
        }
        Set<UUID> set = trustMap.get(ownerUuid);
        return set != null && set.contains(potentialTrustedUuid);
    }

    /**
     * Returns an unmodifiable snapshot set of UUIDs trusted by the specified owner.
     *
     * @param ownerUuid UUID of the container owner.
     * @return Set of trusted player UUIDs (empty if none or owner is null).
     */
    public Set<UUID> getTrustList(UUID ownerUuid) {
        if (ownerUuid == null) {
            return Set.of();
        }
        Set<UUID> set = trustMap.get(ownerUuid);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    /**
     * Bidirectional query: returns an unmodifiable set of owner UUIDs who have granted trust to the specified player.
     *
     * @param trustedUuid UUID of the player.
     * @return Set of owner UUIDs who trust this player.
     */
    public Set<UUID> getTrustingOwners(UUID trustedUuid) {
        if (trustedUuid == null) {
            return Set.of();
        }
        Set<UUID> result = new HashSet<>();
        for (Map.Entry<UUID, Set<UUID>> entry : trustMap.entrySet()) {
            if (entry.getValue().contains(trustedUuid)) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Alias for getTrustingOwners for backward compatibility / query convenience.
     */
    public Set<UUID> getTrustedBy(UUID trustedUuid) {
        return getTrustingOwners(trustedUuid);
    }

    /**
     * Returns the total number of owners who have active trust lists.
     */
    public int getOwnerCount() {
        return trustMap.size();
    }

    /**
     * Clears all in-memory trust relationships.
     */
    public void clear() {
        trustMap.clear();
    }

    /**
     * Saves the current trust relationships to the default storage path.
     *
     * @throws IOException If an I/O error occurs.
     */
    public void save() throws IOException {
        save(this.storagePath);
    }

    /**
     * Saves the current trust relationships to the specified file path atomically.
     *
     * @param file Target file path.
     * @throws IOException If an I/O error occurs.
     */
    public void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file cannot be null");
        ioLock.lock();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String json = toJson();
            Path tmpFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmpFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Loads trust relationships from the default storage path.
     *
     * @throws IOException If an I/O error occurs reading the file.
     */
    public void load() throws IOException {
        load(this.storagePath);
    }

    /**
     * Loads trust relationships from the specified file path.
     *
     * @param file Source file path.
     * @throws IOException If an I/O error occurs reading the file.
     */
    public void load(Path file) throws IOException {
        Objects.requireNonNull(file, "file cannot be null");
        ioLock.lock();
        try {
            if (!Files.exists(file)) {
                return;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            fromJson(content);
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Serializes in-memory trust relationships into a formatted JSON string.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"trusts\": {\n");
        int ownerIdx = 0;
        int totalOwners = trustMap.size();
        for (Map.Entry<UUID, Set<UUID>> entry : trustMap.entrySet()) {
            sb.append("    \"").append(entry.getKey()).append("\": [");
            Set<UUID> trustedSet = entry.getValue();
            int trustedIdx = 0;
            for (UUID trusted : trustedSet) {
                if (trustedIdx > 0) sb.append(", ");
                sb.append("\"").append(trusted).append("\"");
                trustedIdx++;
            }
            sb.append("]");
            ownerIdx++;
            if (ownerIdx < totalOwners) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Deserializes JSON string into internal trust mappings.
     */
    public void fromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        Map<UUID, Set<UUID>> newMap = new HashMap<>();
        Pattern entryPattern = Pattern.compile("\"([0-9a-fA-F\\-]+)\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher entryMatcher = entryPattern.matcher(json);
        while (entryMatcher.find()) {
            String ownerStr = entryMatcher.group(1);
            String arrayStr = entryMatcher.group(2);
            try {
                UUID ownerUuid = UUID.fromString(ownerStr);
                Set<UUID> trustedSet = ConcurrentHashMap.newKeySet();
                Pattern uuidPattern = Pattern.compile("\"([0-9a-fA-F\\-]+)\"");
                Matcher uuidMatcher = uuidPattern.matcher(arrayStr);
                while (uuidMatcher.find()) {
                    try {
                        UUID trustedUuid = UUID.fromString(uuidMatcher.group(1));
                        trustedSet.add(trustedUuid);
                    } catch (IllegalArgumentException ignored) {}
                }
                if (!trustedSet.isEmpty()) {
                    newMap.put(ownerUuid, trustedSet);
                }
            } catch (IllegalArgumentException ignored) {}
        }
        trustMap.clear();
        trustMap.putAll(newMap);
    }

    /**
     * Returns the storage path configured for this TrustManager.
     */
    public Path getStoragePath() {
        return storagePath;
    }
}
