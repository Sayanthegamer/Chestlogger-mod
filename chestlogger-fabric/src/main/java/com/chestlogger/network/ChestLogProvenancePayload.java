package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-to-client payload carrying a resolved Item Provenance & Chain-of-Custody graph.
 */
public record ChestLogProvenancePayload(
        String targetItemId,
        long targetPackedPos,
        String targetDimension,
        int totalSteps,
        String overallConfidence,
        List<ProvenanceDisplayNode> nodes
) implements CustomPacketPayload {

    public static final Type<ChestLogProvenancePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chestlogger", "provenance_payload"));

    public static final StreamCodec<FriendlyByteBuf, ChestLogProvenancePayload> STREAM_CODEC = CustomPacketPayload.codec(
            ChestLogProvenancePayload::write,
            ChestLogProvenancePayload::read
    );

    public ChestLogProvenancePayload {
        targetItemId = targetItemId != null ? targetItemId : "minecraft:chest";
        targetDimension = targetDimension != null ? targetDimension : "minecraft:overworld";
        overallConfidence = overallConfidence != null ? overallConfidence : "UNKNOWN";
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(targetItemId);
        buf.writeLong(targetPackedPos);
        buf.writeUtf(targetDimension);
        buf.writeVarInt(totalSteps);
        buf.writeUtf(overallConfidence);

        buf.writeVarInt(nodes.size());
        for (ProvenanceDisplayNode node : nodes) {
            ProvenanceDisplayNode.write(buf, node);
        }
    }

    public static ChestLogProvenancePayload read(FriendlyByteBuf buf) {
        String targetItemId = buf.readUtf();
        long targetPackedPos = buf.readLong();
        String targetDimension = buf.readUtf();
        int totalSteps = buf.readVarInt();
        String overallConfidence = buf.readUtf();

        int nodeCount = buf.readVarInt();
        List<ProvenanceDisplayNode> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(ProvenanceDisplayNode.read(buf));
        }

        return new ChestLogProvenancePayload(
                targetItemId,
                targetPackedPos,
                targetDimension,
                totalSteps,
                overallConfidence,
                nodes
        );
    }
}
