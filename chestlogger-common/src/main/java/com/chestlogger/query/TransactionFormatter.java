package com.chestlogger.query;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Formats transaction logs into clear, human-readable console and chat text.
 */
public final class TransactionFormatter {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private TransactionFormatter() {}

    public static String formatLine(TransactionLogEntry entry) {
        Objects.requireNonNull(entry, "entry cannot be null");
        String timeStr = TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMs()));
        String actor = (entry.actorName() != null && !entry.actorName().isEmpty()) ? entry.actorName() : entry.actorType().name();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] #%d %s (%s): ", timeStr, entry.sequenceId(), actor, entry.actionType().name()));

        if (entry.deltas().isEmpty()) {
            sb.append("(no slot changes)");
        } else {
            for (int i = 0; i < entry.deltas().size(); i++) {
                if (i > 0) sb.append(", ");
                SlotDelta delta = entry.deltas().get(i);
                String sign = delta.deltaQuantity() > 0 ? "+" : "";
                sb.append(String.format("%s%d %s [slot %d]", sign, delta.deltaQuantity(), delta.itemId(), delta.slotIndex()));
            }
        }

        int x = BlockPosUtil.unpackX(entry.packedBlockPos());
        int y = BlockPosUtil.unpackY(entry.packedBlockPos());
        int z = BlockPosUtil.unpackZ(entry.packedBlockPos());
        sb.append(String.format(" @ (%d, %d, %d)", x, y, z));

        return sb.toString();
    }
}
