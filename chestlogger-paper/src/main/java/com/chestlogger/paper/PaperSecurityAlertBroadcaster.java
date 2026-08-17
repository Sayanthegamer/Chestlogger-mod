package com.chestlogger.paper;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.security.OwnerPresenceState;
import com.chestlogger.security.SecurityIncident;
import com.chestlogger.security.SmartTheftEvaluator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time in-game security alert broadcaster for Paper.
 * Dispatches action-bar HUD warnings and interactive chat components with [Teleport] and [Inspect] buttons to online admins.
 */
public final class PaperSecurityAlertBroadcaster {

    private final Plugin plugin;
    private final SmartTheftEvaluator evaluator;
    private final AlertConfig alertConfig;
    private final com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher;
    private final Map<Long, UUID> containerOwners = new ConcurrentHashMap<>();
    private final Map<Long, String> containerOwnerNames = new ConcurrentHashMap<>();

    public PaperSecurityAlertBroadcaster(Plugin plugin, SmartTheftEvaluator evaluator, AlertConfig alertConfig) {
        this(plugin, evaluator, alertConfig, null);
    }

    public PaperSecurityAlertBroadcaster(Plugin plugin, SmartTheftEvaluator evaluator, AlertConfig alertConfig, com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
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

        // Track container placement / destruction ownership
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

        OwnerPresenceState presence = calculateOwnerPresence(entry, ownerUuid);
        Optional<SecurityIncident> incidentOpt = evaluator.evaluate(entry, ownerUuid, ownerName, presence, alertConfig);

        if (incidentOpt.isPresent()) {
            SecurityIncident incident = incidentOpt.get();
            if (incident.classification().isAlertWorthy()) {
                broadcastAlert(incident);
                if (alertDispatcher != null) {
                    alertDispatcher.dispatchIncident(incident);
                }
            }
        }
    }

    private OwnerPresenceState calculateOwnerPresence(TransactionLogEntry entry, UUID ownerUuid) {
        if (ownerUuid == null) {
            return OwnerPresenceState.offline();
        }

        if (Bukkit.getServer() == null) {
            return OwnerPresenceState.offline();
        }

        try {
            Player ownerPlayer = Bukkit.getPlayer(ownerUuid);
            if (ownerPlayer != null && ownerPlayer.isOnline()) {
                if (ownerPlayer.getWorld().getName().equals(entry.dimension())) {
                    int[] coords = BlockPosUtil.unpack(entry.packedBlockPos());
                    Location loc = ownerPlayer.getLocation();
                    double dx = loc.getBlockX() - coords[0];
                    double dy = loc.getBlockY() - coords[1];
                    double dz = loc.getBlockZ() - coords[2];
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
     * Broadcasts Action-Bar HUD notice and rich interactive JSON chat card with [Teleport] and [Inspect] buttons to admins.
     *
     * @param incident SecurityIncident to broadcast.
     */
    public void broadcastAlert(SecurityIncident incident) {
        if (incident == null || Bukkit.getServer() == null) {
            return;
        }

        int[] coords = BlockPosUtil.unpack(incident.packedPos());
        int x = coords[0], y = coords[1], z = coords[2];

        // 1. Build Action-Bar HUD notice
        Component actionBarComponent = Component.text()
                .append(Component.text("[ALERT] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(incident.classification().name() + " at [" + x + ", " + y + ", " + z + "] by " + incident.actorName(), NamedTextColor.GOLD))
                .build();

        // 2. Build Interactive Chat Component with [Teleport] and [Inspect]
        Component teleportBtn = Component.text("[Teleport]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(String.format(Locale.ROOT, "/tp %d %d %d", x, y, z)))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to [" + x + ", " + y + ", " + z + "]", NamedTextColor.GRAY)));

        Component inspectBtn = Component.text("[Inspect]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(String.format(Locale.ROOT, "/chestlog inspect %d %d %d", x, y, z)))
                .hoverEvent(HoverEvent.showText(Component.text("Click to inspect container at [" + x + ", " + y + ", " + z + "]", NamedTextColor.GRAY)));

        Component chatComponent = Component.text()
                .append(Component.text("[ChestLogger] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(incident.classification().name() + ": ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(incident.summary(), NamedTextColor.YELLOW))
                .append(Component.space())
                .append(teleportBtn)
                .append(Component.space())
                .append(inspectBtn)
                .build();

        // Dispatch to online admins
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("chestlogger.admin")) {
                    player.sendActionBar(actionBarComponent);
                    player.sendMessage(chatComponent);
                }
            }
        } catch (Exception ignored) {}
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
            if (plugin instanceof ChestLoggerPlugin clp) {
                var qe = clp.getQueryEngine();
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
            }
        } catch (Exception ignored) {}
    }
}
