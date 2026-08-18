package com.chestlogger.claim;

import com.chestlogger.event.BlockPosUtil;

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
 * Thread-safe manager for container ownership claims and persistent storage.
 * Handles manual player claiming, double chest linking, radius spatial queries,
 * and atomic JSON persistence.
 */
public final class ClaimManager {

    private static final Logger LOGGER = Logger.getLogger("ChestLogger-ClaimManager");
    private static final String DEFAULT_FILE_NAME = "claims.json";

    private final Path storagePath;
    // Key: dimension + ":" + packedBlockPos
    private final ConcurrentHashMap<String, ClaimEntry> claimsByKey;
    // Index: ownerUuid -> Set of String keys (dimension:packedPos)
    private final ConcurrentHashMap<UUID, Set<String>> ownerIndex;
    private final ReentrantLock ioLock;

    public ClaimManager() {
        this(Path.of(DEFAULT_FILE_NAME));
    }

    public ClaimManager(Path storagePath) {
        Objects.requireNonNull(storagePath, "storagePath cannot be null");
        if (Files.isDirectory(storagePath) || storagePath.toString().endsWith("/") || storagePath.toString().endsWith("\\")) {
            this.storagePath = storagePath.resolve(DEFAULT_FILE_NAME);
        } else {
            this.storagePath = storagePath;
        }
        this.claimsByKey = new ConcurrentHashMap<>();
        this.ownerIndex = new ConcurrentHashMap<>();
        this.ioLock = new ReentrantLock();
    }

    private static String toKey(String dimension, long packedPos) {
        return dimension + ":" + packedPos;
    }

    /**
     * Claims a single container position.
     *
     * @param dimension Dimension identifier.
     * @param packedPos Packed 64-bit coordinate.
     * @param ownerUuid UUID of the owner.
     * @param ownerName Display name of the owner.
     * @return true if claimed successfully, false if input was invalid.
     */
    public boolean claim(String dimension, long packedPos, UUID ownerUuid, String ownerName) {
        if (dimension == null || dimension.isBlank() || ownerUuid == null) {
            return false;
        }
        String key = toKey(dimension, packedPos);
        ClaimEntry oldEntry = claimsByKey.get(key);
        if (oldEntry != null && !oldEntry.ownerUuid().equals(ownerUuid)) {
            // Remove from old owner index
            Set<String> oldSet = ownerIndex.get(oldEntry.ownerUuid());
            if (oldSet != null) {
                oldSet.remove(key);
            }
        }

        ClaimEntry newEntry = new ClaimEntry(dimension, packedPos, ownerUuid, ownerName, null, System.currentTimeMillis());
        claimsByKey.put(key, newEntry);
        ownerIndex.computeIfAbsent(ownerUuid, k -> ConcurrentHashMap.newKeySet()).add(key);
        return true;
    }

    /**
     * Claims a linked double chest with left and right halves atomically.
     *
     * @param dimension Dimension identifier.
     * @param leftPos Left half packed coordinate.
     * @param rightPos Right half packed coordinate.
     * @param ownerUuid UUID of the owner.
     * @param ownerName Display name of the owner.
     * @return true if claimed successfully, false if input was invalid.
     */
    public boolean claim(String dimension, long leftPos, long rightPos, UUID ownerUuid, String ownerName) {
        if (dimension == null || dimension.isBlank() || ownerUuid == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String leftKey = toKey(dimension, leftPos);
        String rightKey = toKey(dimension, rightPos);

        ClaimEntry oldLeft = claimsByKey.get(leftKey);
        if (oldLeft != null && !oldLeft.ownerUuid().equals(ownerUuid)) {
            Set<String> oldSet = ownerIndex.get(oldLeft.ownerUuid());
            if (oldSet != null) oldSet.remove(leftKey);
        }
        ClaimEntry oldRight = claimsByKey.get(rightKey);
        if (oldRight != null && !oldRight.ownerUuid().equals(ownerUuid)) {
            Set<String> oldSet = ownerIndex.get(oldRight.ownerUuid());
            if (oldSet != null) oldSet.remove(rightKey);
        }

        ClaimEntry leftEntry = new ClaimEntry(dimension, leftPos, ownerUuid, ownerName, rightPos, now);
        ClaimEntry rightEntry = new ClaimEntry(dimension, rightPos, ownerUuid, ownerName, leftPos, now);

        claimsByKey.put(leftKey, leftEntry);
        claimsByKey.put(rightKey, rightEntry);

        Set<String> ownerSet = ownerIndex.computeIfAbsent(ownerUuid, k -> ConcurrentHashMap.newKeySet());
        ownerSet.add(leftKey);
        ownerSet.add(rightKey);
        return true;
    }

    /**
     * Unclaims a container position. If the container is part of a double chest,
     * unclaims both halves atomically.
     *
     * @param dimension Dimension identifier.
     * @param packedPos Packed 64-bit coordinate.
     * @return true if claim was found and removed, false otherwise.
     */
    public boolean unclaim(String dimension, long packedPos) {
        if (dimension == null || dimension.isBlank()) {
            return false;
        }
        String key = toKey(dimension, packedPos);
        ClaimEntry entry = claimsByKey.remove(key);
        if (entry == null) {
            return false;
        }

        Set<String> set = ownerIndex.get(entry.ownerUuid());
        if (set != null) {
            set.remove(key);
            if (set.isEmpty()) {
                ownerIndex.remove(entry.ownerUuid());
            }
        }

        if (entry.partnerPackedPos() != null) {
            long partnerPos = entry.partnerPackedPos();
            String partnerKey = toKey(dimension, partnerPos);
            ClaimEntry partnerEntry = claimsByKey.remove(partnerKey);
            if (partnerEntry != null) {
                Set<String> pSet = ownerIndex.get(partnerEntry.ownerUuid());
                if (pSet != null) {
                    pSet.remove(partnerKey);
                    if (pSet.isEmpty()) {
                        ownerIndex.remove(partnerEntry.ownerUuid());
                    }
                }
            }
        }

        return true;
    }

    /**
     * Checks if a container coordinate is claimed.
     */
    public boolean isClaimed(String dimension, long packedPos) {
        if (dimension == null) return false;
        return claimsByKey.containsKey(toKey(dimension, packedPos));
    }

    /**
     * Retrieves the owner UUID for a container.
     */
    public UUID getOwner(String dimension, long packedPos) {
        if (dimension == null) return null;
        ClaimEntry entry = claimsByKey.get(toKey(dimension, packedPos));
        return entry != null ? entry.ownerUuid() : null;
    }

    /**
     * Retrieves the owner display name for a container.
     */
    public String getOwnerName(String dimension, long packedPos) {
        if (dimension == null) return null;
        ClaimEntry entry = claimsByKey.get(toKey(dimension, packedPos));
        return entry != null ? entry.ownerName() : null;
    }

    /**
     * Checks if a specific player is the owner of the container.
     */
    public boolean isOwner(String dimension, long packedPos, UUID playerUuid) {
        if (dimension == null || playerUuid == null) return false;
        UUID owner = getOwner(dimension, packedPos);
        return playerUuid.equals(owner);
    }

    /**
     * Gets the full ClaimEntry for a container position.
     */
    public ClaimEntry getClaim(String dimension, long packedPos) {
        if (dimension == null) return null;
        return claimsByKey.get(toKey(dimension, packedPos));
    }

    /**
     * Returns the total number of claimed container block positions.
     */
    public int getClaimCount() {
        return claimsByKey.size();
    }

    /**
     * Gets all active claims belonging to a player.
     */
    public Collection<ClaimEntry> getClaimsByOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return Collections.emptyList();
        }
        Set<String> keys = ownerIndex.get(ownerUuid);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<ClaimEntry> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            ClaimEntry entry = claimsByKey.get(key);
            if (entry != null) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Finds all claims within a spatial sphere radius around coordinates.
     */
    public List<ClaimEntry> findClaimsInRadius(String dimension, int centerX, int centerY, int centerZ, int radiusBlocks) {
        if (dimension == null) {
            return Collections.emptyList();
        }
        double rSq = (double) radiusBlocks * radiusBlocks;
        List<ClaimEntry> matches = new ArrayList<>();

        for (ClaimEntry entry : claimsByKey.values()) {
            if (!dimension.equals(entry.dimension())) {
                continue;
            }
            int[] coords = BlockPosUtil.unpack(entry.packedBlockPos());
            double dx = coords[0] - centerX;
            double dy = coords[1] - centerY;
            double dz = coords[2] - centerZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= rSq) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /**
     * Finds all claims within a spatial sphere radius around packed position.
     */
    public List<ClaimEntry> findClaimsInRadius(String dimension, long centerPackedPos, int radiusBlocks) {
        int[] c = BlockPosUtil.unpack(centerPackedPos);
        return findClaimsInRadius(dimension, c[0], c[1], c[2], radiusBlocks);
    }

    /**
     * Clears all in-memory claims.
     */
    public void clear() {
        claimsByKey.clear();
        ownerIndex.clear();
    }

    /**
     * Saves claims to the default storage path.
     */
    public void save() throws IOException {
        save(this.storagePath);
    }

    /**
     * Saves claims to a specified path atomically.
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
     * Loads claims from the default storage path.
     */
    public void load() throws IOException {
        load(this.storagePath);
    }

    /**
     * Loads claims from a specified file.
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
     * Serializes all claims into formatted JSON string.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"claims\": [\n");

        int idx = 0;
        int size = claimsByKey.size();
        for (ClaimEntry entry : claimsByKey.values()) {
            sb.append("    {");
            sb.append("\"dimension\":\"").append(escapeJson(entry.dimension())).append("\",");
            sb.append("\"pos\":").append(entry.packedBlockPos()).append(",");
            sb.append("\"ownerUuid\":\"").append(entry.ownerUuid()).append("\",");
            sb.append("\"ownerName\":\"").append(escapeJson(entry.ownerName() != null ? entry.ownerName() : "")).append("\",");
            if (entry.partnerPackedPos() != null) {
                sb.append("\"partnerPos\":").append(entry.partnerPackedPos()).append(",");
            }
            sb.append("\"claimedAt\":").append(entry.claimedAtMs());
            sb.append("}");
            idx++;
            if (idx < size) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Deserializes JSON string into claim state.
     */
    public void fromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        Map<String, ClaimEntry> newClaims = new HashMap<>();
        Map<UUID, Set<String>> newOwnerIndex = new HashMap<>();

        Pattern objPattern = Pattern.compile("\\{([^{}]+)\\}");
        Matcher objMatcher = objPattern.matcher(json);

        Pattern dimPat = Pattern.compile("\"dimension\"\\s*:\\s*\"([^\"]+)\"");
        Pattern posPat = Pattern.compile("\"pos\"\\s*:\\s*(-?\\d+)");
        Pattern ownerUuidPat = Pattern.compile("\"ownerUuid\"\\s*:\\s*\"([0-9a-fA-F\\-]+)\"");
        Pattern ownerNamePat = Pattern.compile("\"ownerName\"\\s*:\\s*\"([^\"]*)\"");
        Pattern partnerPat = Pattern.compile("\"partnerPos\"\\s*:\\s*(-?\\d+)");
        Pattern timePat = Pattern.compile("\"claimedAt\"\\s*:\\s*(\\d+)");

        while (objMatcher.find()) {
            String block = objMatcher.group(1);
            Matcher mDim = dimPat.matcher(block);
            Matcher mPos = posPat.matcher(block);
            Matcher mOwner = ownerUuidPat.matcher(block);
            Matcher mName = ownerNamePat.matcher(block);
            Matcher mPartner = partnerPat.matcher(block);
            Matcher mTime = timePat.matcher(block);

            if (mDim.find() && mPos.find() && mOwner.find()) {
                String dim = mDim.group(1);
                long pos = Long.parseLong(mPos.group(1));
                UUID ownerUuid = UUID.fromString(mOwner.group(1));
                String ownerName = mName.find() ? mName.group(1) : "";
                Long partnerPos = mPartner.find() ? Long.parseLong(mPartner.group(1)) : null;
                long claimedAt = mTime.find() ? Long.parseLong(mTime.group(1)) : System.currentTimeMillis();

                ClaimEntry entry = new ClaimEntry(dim, pos, ownerUuid, ownerName, partnerPos, claimedAt);
                String key = toKey(dim, pos);
                newClaims.put(key, entry);
                newOwnerIndex.computeIfAbsent(ownerUuid, k -> ConcurrentHashMap.newKeySet()).add(key);
            }
        }

        claimsByKey.clear();
        claimsByKey.putAll(newClaims);
        ownerIndex.clear();
        ownerIndex.putAll(newOwnerIndex);
    }

    public Path getStoragePath() {
        return storagePath;
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
