package com.chestlogger.container;

import com.chestlogger.event.SlotDelta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of container inventory slots for computing exact transaction diffs.
 */
public final class ContainerSnapshot {
    private final SlotSnapshot[] slots;

    public ContainerSnapshot(int slotCount) {
        if (slotCount < 0) {
            throw new IllegalArgumentException("Slot count cannot be negative: " + slotCount);
        }
        this.slots = new SlotSnapshot[slotCount];
        for (int i = 0; i < slotCount; i++) {
            slots[i] = new SlotSnapshot(i, "minecraft:air", 0, 0L);
        }
    }

    public void setSlot(int slotIndex, String itemId, int count, long fingerprint) {
        if (slotIndex < 0 || slotIndex >= slots.length) {
            return;
        }
        slots[slotIndex] = new SlotSnapshot(
                slotIndex,
                itemId == null ? "minecraft:air" : itemId,
                count,
                fingerprint
        );
    }

    public SlotSnapshot getSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.length) {
            return SlotSnapshot.EMPTY;
        }
        return slots[slotIndex];
    }

    public ContainerSnapshot copy() {
        ContainerSnapshot copy = new ContainerSnapshot(this.slots.length);
        for (int i = 0; i < this.slots.length; i++) {
            copy.slots[i] = this.slots[i];
        }
        return copy;
    }

    public int size() {
        return slots.length;
    }

    /**
     * Computes the exact slot deltas between this snapshot (pre) and postSnapshot.
     */
    public List<SlotDelta> diff(ContainerSnapshot postSnapshot) {
        Objects.requireNonNull(postSnapshot, "postSnapshot cannot be null");
        int maxSlots = Math.max(this.slots.length, postSnapshot.slots.length);
        List<SlotDelta> deltas = new ArrayList<>();

        for (int i = 0; i < maxSlots; i++) {
            SlotSnapshot pre = (i < this.slots.length) ? this.slots[i] : SlotSnapshot.EMPTY;
            SlotSnapshot post = (i < postSnapshot.slots.length) ? postSnapshot.slots[i] : SlotSnapshot.EMPTY;

            boolean preEmpty = pre.isEmpty();
            boolean postEmpty = post.isEmpty();

            if (preEmpty && postEmpty) {
                continue;
            }

            if (preEmpty && !postEmpty) {
                // Item inserted into previously empty slot
                deltas.add(new SlotDelta(
                        i,
                        post.itemId(),
                        post.count(),
                        0,
                        post.count(),
                        post.metadataFingerprint()
                ));
            } else if (!preEmpty && postEmpty) {
                // Item completely removed from slot
                deltas.add(new SlotDelta(
                        i,
                        pre.itemId(),
                        -pre.count(),
                        pre.count(),
                        0,
                        pre.metadataFingerprint()
                ));
            } else {
                // Both non-empty: check same item type vs different item type (swap)
                boolean sameItem = Objects.equals(pre.itemId(), post.itemId()) &&
                        pre.metadataFingerprint() == post.metadataFingerprint();

                if (sameItem) {
                    int deltaQty = post.count() - pre.count();
                    if (deltaQty != 0) {
                        deltas.add(new SlotDelta(
                                i,
                                post.itemId(),
                                deltaQty,
                                pre.count(),
                                post.count(),
                                post.metadataFingerprint()
                        ));
                    }
                } else {
                    // Swapped with different item or different components
                    deltas.add(new SlotDelta(
                            i,
                            pre.itemId(),
                            -pre.count(),
                            pre.count(),
                            0,
                            pre.metadataFingerprint()
                    ));
                    deltas.add(new SlotDelta(
                            i,
                            post.itemId(),
                            post.count(),
                            0,
                            post.count(),
                            post.metadataFingerprint()
                    ));
                }
            }
        }

        return deltas;
    }
}
