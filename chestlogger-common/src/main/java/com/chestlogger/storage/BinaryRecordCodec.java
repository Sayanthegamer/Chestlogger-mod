package com.chestlogger.storage;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * High-performance binary encoder and decoder for TransactionLogEntry records.
 */
public final class BinaryRecordCodec {
    public static final byte FLAG_IS_AUTOMATION = 0x10;
    public static final byte FLAG_HAS_PLAYER_NAME = 0x20;

    private BinaryRecordCodec() {}

    public static void encode(
            OutputStream out,
            TransactionLogEntry entry,
            StringTableDictionary stringDict,
            long baseSeqId,
            long baseTimestampMs
    ) throws IOException {
        // 1. Sequence & Time Deltas
        VarIntUtil.writeVarLong(out, entry.sequenceId() - baseSeqId);
        VarIntUtil.writeVarLong(out, Math.max(0, entry.timestampMs() - baseTimestampMs));

        // 2. Transaction UUID (16 bytes)
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(entry.transactionId().getMostSignificantBits());
        bb.putLong(entry.transactionId().getLeastSignificantBits());
        out.write(bb.array());

        // 3. Action and Flags byte
        byte mask = (byte) (entry.actionType().getWireId() & 0x0F);
        if (entry.isAutomation()) {
            mask |= FLAG_IS_AUTOMATION;
        }
        if (entry.actorName() != null && !entry.actorName().isEmpty()) {
            mask |= FLAG_HAS_PLAYER_NAME;
        }
        out.write(mask);

        // 4. Actor Details
        out.write(entry.actorType().getWireId());
        if (!entry.isAutomation()) {
            ByteBuffer actorBuf = ByteBuffer.allocate(16);
            UUID playerUuid = entry.actorUuid() != null ? entry.actorUuid() : new UUID(0L, 0L);
            actorBuf.putLong(playerUuid.getMostSignificantBits());
            actorBuf.putLong(playerUuid.getLeastSignificantBits());
            out.write(actorBuf.array());
        }
        if ((mask & FLAG_HAS_PLAYER_NAME) != 0) {
            byte[] nameBytes = entry.actorName().getBytes(StandardCharsets.UTF_8);
            VarIntUtil.writeVarInt(out, nameBytes.length);
            out.write(nameBytes);
        }

        // 5. Dimension & Packed Position
        int dimId = stringDict.getOrAssign(entry.dimension());
        VarIntUtil.writeVarInt(out, dimId);
        VarIntUtil.writeVarLong(out, entry.packedBlockPos());

        // 6. Slot Deltas
        VarIntUtil.writeVarInt(out, entry.deltas().size());
        for (SlotDelta delta : entry.deltas()) {
            VarIntUtil.writeVarInt(out, delta.slotIndex());
            int itemId = stringDict.getOrAssign(delta.itemId());
            VarIntUtil.writeVarInt(out, itemId);
            VarIntUtil.writeSignedVarInt(out, delta.deltaQuantity());
            VarIntUtil.writeVarInt(out, delta.preCount());
            VarIntUtil.writeVarInt(out, delta.postCount());
            VarIntUtil.writeVarLong(out, delta.metadataFingerprint());
        }
    }

    public static TransactionLogEntry decode(
            InputStream in,
            StringTableDictionary stringDict,
            long baseSeqId,
            long baseTimestampMs
    ) throws IOException {
        long seqId = baseSeqId + VarIntUtil.readVarLong(in);
        long timestamp = baseTimestampMs + VarIntUtil.readVarLong(in);

        byte[] txBytes = in.readNBytes(16);
        if (txBytes.length < 16) {
            throw new EOFException("Incomplete transaction UUID");
        }
        ByteBuffer txBuf = ByteBuffer.wrap(txBytes);
        UUID txId = new UUID(txBuf.getLong(), txBuf.getLong());

        int maskByte = in.read();
        if (maskByte == -1) throw new EOFException("EOF reading action mask");
        byte mask = (byte) maskByte;

        ActionType actionType = ActionType.fromWireId((byte) (mask & 0x0F));
        boolean isAutomation = (mask & FLAG_IS_AUTOMATION) != 0;
        boolean hasPlayerName = (mask & FLAG_HAS_PLAYER_NAME) != 0;

        int actorTypeByte = in.read();
        if (actorTypeByte == -1) throw new EOFException("EOF reading actor type");
        ActorType actorType = ActorType.fromWireId((byte) actorTypeByte);

        UUID actorUuid = null;
        if (!isAutomation) {
            byte[] actorBytes = in.readNBytes(16);
            if (actorBytes.length < 16) throw new EOFException("EOF reading actor UUID");
            ByteBuffer actorBuf = ByteBuffer.wrap(actorBytes);
            actorUuid = new UUID(actorBuf.getLong(), actorBuf.getLong());
        }

        String actorName = "";
        if (hasPlayerName) {
            int nameLen = VarIntUtil.readVarInt(in);
            byte[] nameBytes = in.readNBytes(nameLen);
            actorName = new String(nameBytes, StandardCharsets.UTF_8);
        }

        int dimId = VarIntUtil.readVarInt(in);
        String dimension = stringDict.getString(dimId);
        long packedPos = VarIntUtil.readVarLong(in);

        int deltaCount = VarIntUtil.readVarInt(in);
        List<SlotDelta> deltas = new ArrayList<>(deltaCount);
        for (int i = 0; i < deltaCount; i++) {
            int slotIndex = VarIntUtil.readVarInt(in);
            int itemId = VarIntUtil.readVarInt(in);
            String itemStr = stringDict.getString(itemId);
            int deltaQty = VarIntUtil.readSignedVarInt(in);
            int preCount = VarIntUtil.readVarInt(in);
            int postCount = VarIntUtil.readVarInt(in);
            long fingerprint = VarIntUtil.readVarLong(in);

            deltas.add(new SlotDelta(slotIndex, itemStr, deltaQty, preCount, postCount, fingerprint));
        }

        return new TransactionLogEntry(
                seqId, timestamp, txId, actionType, actorType,
                actorUuid, actorName, dimension, packedPos, deltas
        );
    }
}
