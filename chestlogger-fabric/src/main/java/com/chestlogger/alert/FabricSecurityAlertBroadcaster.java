package com.chestlogger.alert;

import com.chestlogger.claim.ClaimManager;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.security.IncidentClassification;
import com.chestlogger.security.OwnerPresenceState;
import com.chestlogger.security.SecurityIncident;
import com.chestlogger.security.SmartTheftEvaluator;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Real-time in-game security alert broadcaster for Fabric.
 * Dispatches action-bar HUD warnings and interactive chat cards to online operators/admins.
 */
public final class FabricSecurityAlertBroadcaster {

    private final Supplier<MinecraftServer> serverSupplier;
    private final SmartTheftEvaluator evaluator;
    private final AlertConfig alertConfig;
    private final com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher;
    private final ClaimManager claimManager;

    public FabricSecurityAlertBroadcaster(Supplier<MinecraftServer> serverSupplier, SmartTheftEvaluator evaluator, AlertConfig alertConfig) {
        this(serverSupplier, evaluator, alertConfig, null, new ClaimManager());
    }

    public FabricSecurityAlertBroadcaster(Supplier<MinecraftServer> serverSupplier, SmartTheftEvaluator evaluator, AlertConfig alertConfig, com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher) {
        this(serverSupplier, evaluator, alertConfig, alertDispatcher, new ClaimManager());
    }

    public FabricSecurityAlertBroadcaster(Supplier<MinecraftServer> serverSupplier, SmartTheftEvaluator evaluator, AlertConfig alertConfig, com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher, ClaimManager claimManager) {
        this.serverSupplier = Objects.requireNonNull(serverSupplier, "serverSupplier cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
        this.alertConfig = Objects.requireNonNull(alertConfig, "alertConfig cannot be null");
        this.alertDispatcher = alertDispatcher;
        this.claimManager = claimManager != null ? claimManager : new ClaimManager();
    }

    /**
     * Evaluates a transaction log entry and broadcasts an in-game alert if it constitutes an alert-worthy theft or raid.
     *
     * @param entry TransactionLogEntry to evaluate.
     */
    public void processTransaction(TransactionLogEntry entry) {
        if (entry == null) {
            return;
        }

        if (entry.actionType() == ActionType.CONTAINER_PLACE) {
            if (entry.actorUuid() != null) {
                claimManager.claim(entry.dimension(), entry.packedBlockPos(), entry.actorUuid(), entry.actorName());
            }
        }

        UUID ownerUuid = claimManager.getOwner(entry.dimension(), entry.packedBlockPos());
        String ownerName = claimManager.getOwnerName(entry.dimension(), entry.packedBlockPos());

        if (ownerUuid == null) {
            resolveHistoricalOwnerIfAbsent(entry.packedBlockPos(), entry.dimension());
            ownerUuid = claimManager.getOwner(entry.dimension(), entry.packedBlockPos());
            ownerName = claimManager.getOwnerName(entry.dimension(), entry.packedBlockPos());
        }

        if (entry.actionType() == ActionType.CONTAINER_BREAK) {
            claimManager.unclaim(entry.dimension(), entry.packedBlockPos());
        }

        MinecraftServer server = serverSupplier.get();
        OwnerPresenceState presence = calculateOwnerPresence(server, entry, ownerUuid);
        Optional<SecurityIncident> incidentOpt = evaluator.evaluate(entry, ownerUuid, ownerName, presence, alertConfig);

        if (incidentOpt.isPresent()) {
            SecurityIncident incident = incidentOpt.get();
            if (incident.classification().isAlertWorthy()) {
                broadcastAlert(server, incident);
                if (alertDispatcher != null) {
                    alertDispatcher.dispatchIncident(incident);
                }
            }
        }
    }

    private OwnerPresenceState calculateOwnerPresence(MinecraftServer server, TransactionLogEntry entry, UUID ownerUuid) {
        if (ownerUuid == null || server == null) {
            return OwnerPresenceState.offline();
        }

        try {
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(ownerUuid);
            if (ownerPlayer != null) {
                if (ownerPlayer.level().dimension().identifier().toString().equals(entry.dimension())) {
                    int[] coords = BlockPosUtil.unpack(entry.packedBlockPos());
                    double dx = ownerPlayer.getX() - coords[0];
                    double dy = ownerPlayer.getY() - coords[1];
                    double dz = ownerPlayer.getZ() - coords[2];
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    return OwnerPresenceState.online(dist);
                } else {
                    return OwnerPresenceState.online(Double.MAX_VALUE);
                }
            }
        } catch (Exception ignored) {}

        return OwnerPresenceState.offline();
    }

    /**
     * Broadcasts Action-Bar HUD notice and interactive chat card with [Teleport] and [Inspect] buttons.
     *
     * @param server MinecraftServer instance.
     * @param incident SecurityIncident to broadcast.
     */
    public void broadcastAlert(MinecraftServer server, SecurityIncident incident) {
        if (incident == null || server == null) {
            return;
        }

        int[] coords = BlockPosUtil.unpack(incident.packedPos());
        int x = coords[0], y = coords[1], z = coords[2];

        Component tpButton = Component.literal("[Teleport]")
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(String.format(Locale.ROOT, "/tp %d %d %d", x, y, z)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Teleport to " + x + " " + y + " " + z))));

        Component inspectButton = Component.literal("[Inspect]")
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(String.format(Locale.ROOT, "/chestlog inspect %d %d %d", x, y, z)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Inspect container at " + x + " " + y + " " + z))));

        Component chatMsg = Component.literal("§c§l[ChestLogger] §6§l" + incident.classification().name() + ": §e" + incident.summary() + " ")
                .append(tpButton)
                .append(Component.literal(" "))
                .append(inspectButton);

        Component actionMsg = Component.literal(String.format(Locale.ROOT, "§c§l[ALERT] §6%s at [%d, %d, %d] by %s",
                incident.classification().name(), x, y, z, incident.actorName()));

        server.execute(() -> {
            try {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (server.getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()))) {
                        // Send subtle Action-Bar HUD notice
                        player.sendSystemMessage(actionMsg, true);
                        // Send interactive chat card
                        player.sendSystemMessage(chatMsg, false);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public SmartTheftEvaluator getEvaluator() {
        return evaluator;
    }

    public ClaimManager getClaimManager() {
        return claimManager;
    }

    public void registerContainerOwner(long packedPos, UUID ownerUuid, String ownerName) {
        registerContainerOwner("minecraft:overworld", packedPos, ownerUuid, ownerName);
    }

    public void registerContainerOwner(String dimension, long packedPos, UUID ownerUuid, String ownerName) {
        if (ownerUuid != null) {
            claimManager.claim(dimension != null ? dimension : "minecraft:overworld", packedPos, ownerUuid, ownerName);
        }
    }

    private void resolveHistoricalOwnerIfAbsent(long packedPos, String dimension) {
        if (claimManager.isClaimed(dimension, packedPos)) {
            return;
        }
        try {
            var qe = com.chestlogger.ChestLoggerMod.getQueryEngine();
            if (qe != null) {
                var filter = com.chestlogger.index.IndexQueryFilter.builder()
                        .dimension(dimension)
                        .exactBlockPos(packedPos)
                        .limit(50)
                        .build();
                java.util.List<TransactionLogEntry> history = qe.fetchRecords(filter);
                for (TransactionLogEntry e : history) {
                    if (e.actorUuid() != null && (e.actionType() == ActionType.CONTAINER_PLACE || e.actionType() == ActionType.PLACE)) {
                        claimManager.claim(dimension, packedPos, e.actorUuid(), e.actorName());
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}

