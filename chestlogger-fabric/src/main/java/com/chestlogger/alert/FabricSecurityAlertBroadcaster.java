package com.chestlogger.alert;

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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<Long, UUID> containerOwners = new ConcurrentHashMap<>();
    private final Map<Long, String> containerOwnerNames = new ConcurrentHashMap<>();

    public FabricSecurityAlertBroadcaster(Supplier<MinecraftServer> serverSupplier, SmartTheftEvaluator evaluator, AlertConfig alertConfig) {
        this(serverSupplier, evaluator, alertConfig, null);
    }

    public FabricSecurityAlertBroadcaster(Supplier<MinecraftServer> serverSupplier, SmartTheftEvaluator evaluator, AlertConfig alertConfig, com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher) {
        this.serverSupplier = Objects.requireNonNull(serverSupplier, "serverSupplier cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
        this.alertConfig = Objects.requireNonNull(alertConfig, "alertConfig cannot be null");
        this.alertDispatcher = alertDispatcher;
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
                containerOwners.put(entry.packedBlockPos(), entry.actorUuid());
                if (entry.actorName() != null) {
                    containerOwnerNames.put(entry.packedBlockPos(), entry.actorName());
                }
            }
        }

        UUID ownerUuid = containerOwners.get(entry.packedBlockPos());
        String ownerName = containerOwnerNames.get(entry.packedBlockPos());

        if (ownerUuid == null) {
            resolveHistoricalOwnerIfAbsent(entry.packedBlockPos(), entry.dimension());
            ownerUuid = containerOwners.get(entry.packedBlockPos());
            ownerName = containerOwnerNames.get(entry.packedBlockPos());
        }

        if (entry.actionType() == ActionType.CONTAINER_BREAK) {
            containerOwners.remove(entry.packedBlockPos());
            containerOwnerNames.remove(entry.packedBlockPos());
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

    public void registerContainerOwner(long packedPos, UUID ownerUuid, String ownerName) {
        if (ownerUuid != null) {
            containerOwners.put(packedPos, ownerUuid);
            if (ownerName != null) {
                containerOwnerNames.put(packedPos, ownerName);
            }
        }
    }

    private void resolveHistoricalOwnerIfAbsent(long packedPos, String dimension) {
        if (containerOwners.containsKey(packedPos)) {
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
                        containerOwners.put(packedPos, e.actorUuid());
                        if (e.actorName() != null) {
                            containerOwnerNames.put(packedPos, e.actorName());
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
