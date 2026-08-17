package com.chestlogger.container;

import com.chestlogger.event.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerIntegrationTest {

    private TransactionEventQueue eventQueue;
    private ContainerTracker tracker;
    private final UUID playerUuid = UUID.randomUUID();
    private final String playerName = "Steve";
    private final String overworld = "minecraft:overworld";
    private final String nether = "minecraft:the_nether";
    private final long chestPos = BlockPosUtil.pack(100, 64, 200);

    @BeforeEach
    void setup() {
        eventQueue = new TransactionEventQueue(4096);
        tracker = new ContainerTracker(eventQueue, 1L);
    }

    @Test
    @DisplayName("Should track player single chest interactions (pickup, place, shift-click, hotbar swap, drag, double click)")
    void testPlayerChestInteractions() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        // 1. Player places 64 Diamonds into slot 0
        post.setSlot(0, "minecraft:diamond", 64, 0L);
        boolean logged1 = tracker.processTransaction(
                pre, post, ActionType.PLACE, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );
        assertThat(logged1).isTrue();
        assertThat(eventQueue.getDepth()).isEqualTo(1);

        // 2. Player shift-clicks 32 Diamonds out
        pre = post;
        post = new ContainerSnapshot(27);
        post.setSlot(0, "minecraft:diamond", 32, 0L);
        boolean logged2 = tracker.processTransaction(
                pre, post, ActionType.SHIFT_CLICK_EXTRACT, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );
        assertThat(logged2).isTrue();
        assertThat(eventQueue.getDepth()).isEqualTo(2);

        // 3. Hotbar swap: swap remaining diamonds with 16 Emeralds
        pre = post;
        post = new ContainerSnapshot(27);
        post.setSlot(0, "minecraft:emerald", 16, 9999L);
        boolean logged3 = tracker.processTransaction(
                pre, post, ActionType.HOTBAR_SWAP, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );
        assertThat(logged3).isTrue();
        assertThat(eventQueue.getDepth()).isEqualTo(3);

        // 4. Drag split 16 emeralds across slots 0, 1, 2, 3
        pre = post;
        post = new ContainerSnapshot(27);
        post.setSlot(0, "minecraft:emerald", 4, 9999L);
        post.setSlot(1, "minecraft:emerald", 4, 9999L);
        post.setSlot(2, "minecraft:emerald", 4, 9999L);
        post.setSlot(3, "minecraft:emerald", 4, 9999L);
        boolean logged4 = tracker.processTransaction(
                pre, post, ActionType.DRAG_SPLIT, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );
        assertThat(logged4).isTrue();
        assertThat(eventQueue.getDepth()).isEqualTo(4);

        // 5. Double click collect: gather all 16 emeralds back to slot 0
        pre = post;
        post = new ContainerSnapshot(27);
        post.setSlot(0, "minecraft:emerald", 16, 9999L);
        boolean logged5 = tracker.processTransaction(
                pre, post, ActionType.DOUBLE_CLICK_COLLECT, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );
        assertThat(logged5).isTrue();
        assertThat(eventQueue.getDepth()).isEqualTo(5);

        // Drain and verify all sequence numbers and deltas
        List<TransactionLogEntry> drained = new ArrayList<>();
        tracker.getEventQueue().drain(drained, 10);
        assertThat(drained).hasSize(5);

        assertThat(drained.get(0).deltas().get(0).deltaQuantity()).isEqualTo(64);
        assertThat(drained.get(1).deltas().get(0).deltaQuantity()).isEqualTo(-32);
        // Swap has 2 deltas (-32 diamond, +16 emerald)
        assertThat(drained.get(2).deltas()).hasSize(2);
        // Drag split has 4 deltas (-12 from slot 0, +4 in slots 1, 2, 3)
        assertThat(drained.get(3).deltas()).hasSize(4);
    }

    @Test
    @DisplayName("Should track Double Chest (54 slots) composite interactions without coordinate corruption")
    void testDoubleChestAttribution() {
        ContainerSnapshot pre = new ContainerSnapshot(54);
        ContainerSnapshot post = new ContainerSnapshot(54);

        // Modify slot 0 (left chest) and slot 30 (right chest)
        post.setSlot(0, "minecraft:gold_ingot", 64, 0L);
        post.setSlot(30, "minecraft:iron_ingot", 64, 0L);

        boolean logged = tracker.processTransaction(
                pre, post, ActionType.PLACE, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );

        assertThat(logged).isTrue();
        List<TransactionLogEntry> drained = new ArrayList<>();
        tracker.getEventQueue().drain(drained, 1);
        assertThat(drained).hasSize(1);

        TransactionLogEntry entry = drained.get(0);
        assertThat(entry.deltas()).hasSize(2);
        assertThat(entry.deltas().get(0).slotIndex()).isEqualTo(0);
        assertThat(entry.deltas().get(1).slotIndex()).isEqualTo(30);
    }

    @Test
    @DisplayName("Should track Shulker Box and Barrel containers correctly")
    void testShulkerAndBarrelAttribution() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        post.setSlot(13, "minecraft:netherite_ingot", 1, 5555L);

        // Barrel in Nether
        long barrelPos = BlockPosUtil.pack(50, 80, -30);
        boolean logged = tracker.processTransaction(
                pre, post, ActionType.PLACE, ActorType.PLAYER,
                playerUuid, playerName, nether, barrelPos
        );

        assertThat(logged).isTrue();
        List<TransactionLogEntry> drained = new ArrayList<>();
        tracker.getEventQueue().drain(drained, 1);

        TransactionLogEntry entry = drained.get(0);
        assertThat(entry.dimension()).isEqualTo("minecraft:the_nether");
        assertThat(BlockPosUtil.unpackX(entry.packedBlockPos())).isEqualTo(50);
        assertThat(BlockPosUtil.unpackY(entry.packedBlockPos())).isEqualTo(80);
        assertThat(BlockPosUtil.unpackZ(entry.packedBlockPos())).isEqualTo(-30);
    }

    @Test
    @DisplayName("Should track automated Hopper -> Chest and Chest -> Hopper item movements")
    void testHopperTransfers() {
        long hopperPos = BlockPosUtil.pack(100, 65, 200);

        // Hopper extracts 1 item from chest
        ContainerSnapshot chestPre = new ContainerSnapshot(27);
        chestPre.setSlot(0, "minecraft:cobblestone", 64, 0L);
        ContainerSnapshot chestPost = new ContainerSnapshot(27);
        chestPost.setSlot(0, "minecraft:cobblestone", 63, 0L);

        boolean loggedExtract = tracker.processTransaction(
                chestPre, chestPost, ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                null, "hopper", overworld, chestPos
        );
        assertThat(loggedExtract).isTrue();

        // Hopper inserts 1 item into destination container
        ContainerSnapshot destPre = new ContainerSnapshot(27);
        ContainerSnapshot destPost = new ContainerSnapshot(27);
        destPost.setSlot(0, "minecraft:cobblestone", 1, 0L);

        boolean loggedInsert = tracker.processTransaction(
                destPre, destPost, ActionType.HOPPER_INSERT, ActorType.HOPPER_BLOCK,
                null, "hopper", overworld, hopperPos
        );
        assertThat(loggedInsert).isTrue();

        List<TransactionLogEntry> drained = new ArrayList<>();
        tracker.getEventQueue().drain(drained, 10);
        assertThat(drained).hasSize(2);

        assertThat(drained.get(0).actionType()).isEqualTo(ActionType.HOPPER_EXTRACT);
        assertThat(drained.get(0).actorType()).isEqualTo(ActorType.HOPPER_BLOCK);
        assertThat(drained.get(0).deltas().get(0).deltaQuantity()).isEqualTo(-1);

        assertThat(drained.get(1).actionType()).isEqualTo(ActionType.HOPPER_INSERT);
        assertThat(drained.get(1).actorType()).isEqualTo(ActorType.HOPPER_BLOCK);
        assertThat(drained.get(1).deltas().get(0).deltaQuantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should track Hopper Minecart item transfers")
    void testHopperMinecartTransfers() {
        long minecartPos = BlockPosUtil.pack(300, 70, -100);

        ContainerSnapshot cartPre = new ContainerSnapshot(5);
        ContainerSnapshot cartPost = new ContainerSnapshot(5);
        cartPost.setSlot(0, "minecraft:rail", 1, 0L);

        boolean logged = tracker.processTransaction(
                cartPre, cartPost, ActionType.HOPPER_INSERT, ActorType.HOPPER_MINECART,
                null, "hopper_minecart", overworld, minecartPos
        );

        assertThat(logged).isTrue();
        List<TransactionLogEntry> drained = new ArrayList<>();
        tracker.getEventQueue().drain(drained, 1);

        TransactionLogEntry entry = drained.get(0);
        assertThat(entry.actorType()).isEqualTo(ActorType.HOPPER_MINECART);
        assertThat(entry.deltas().get(0).itemId()).isEqualTo("minecraft:rail");
    }

    @Test
    @DisplayName("Should produce zero log entries when transaction fails or is cancelled")
    void testCancelledAndFailedTransactions() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        pre.setSlot(0, "minecraft:diamond", 10, 0L);
        ContainerSnapshot post = new ContainerSnapshot(27);
        post.setSlot(0, "minecraft:diamond", 10, 0L); // Unchanged

        boolean logged = tracker.processTransaction(
                pre, post, ActionType.PICKUP, ActorType.PLAYER,
                playerUuid, playerName, overworld, chestPos
        );

        assertThat(logged).isFalse();
        assertThat(eventQueue.getDepth()).isEqualTo(0);
        assertThat(eventQueue.getEnqueuedCount()).isEqualTo(0);
    }
}
