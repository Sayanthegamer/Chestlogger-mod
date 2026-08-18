package com.chestlogger.paper;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.claim.ClaimManager;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Real-time in-game security alert broadcaster for Paper.
 * Dispatches action-bar HUD warnings and interactive chat components with [Teleport] and [Inspect] buttons to online admins.
 */
public final class PaperSecurityAlertBroadcaster {

    private final Plugin plugin;
    private final SmartTheftEvaluator evaluator;
    private final AlertConfig alertConfig;
    private final com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher;
    private final ClaimManager claimManager;

    public PaperSecurityAlertBroadcaster(Plugin plugin, SmartTheftEvaluator evaluator, AlertConfig alertConfig) {
        this(plugin, evaluator, alertConfig, null, new ClaimManager());
    }

    public PaperSecurityAlertBroadcaster(Plugin plugin, SmartTheftEvaluator evaluator, AlertConfig alertConfig, com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher) {
        this(plugin, evaluator, alertConfig, alertDispatcher, new ClaimManager());
    }

    public PaperSecurityAlertBroadcaster(Plugin plugin, SmartTheftEvaluator evaluator, AlertConfig alertConfig, com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher, ClaimManager claimManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
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

        // Track container placement / destruction ownership
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

        try {
            Player ownerPlayer = Bukkit.getPlayer(ownerUuid);
            if (ownerPlayer != null && ownerPlayer.isOnline()) {
                Location ownerLoc = ownerPlayer.getLocation();
                int[] coords = BlockPosUtil.unpack(entry.packedBlockPos());

                if (ownerLoc.getWorld() != null && ownerLoc.getWorld().getName().equals(entry.dimension())) {
                    double dx = ownerLoc.getX() - coords[0];
                    double dy = ownerLoc.getY() - coords[1];
                    double dz = ownerLoc.getZ() - coords[2];
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
     * Broadcasts Action-Bar HUD notice and interactive chat component with [Teleport] and [Inspect] buttons.
     *
     * @param incident SecurityIncident to broadcast.
     */
    public void broadcastAlert(SecurityIncident incident) {
        if (incident == null) {
            return;
        }

        int[] coords = BlockPosUtil.unpack(incident.packedPos());
        int x = coords[0], y = coords[1], z = coords[2];

        // 1. Subtle Action-Bar HUD Warning
        Component actionBarComponent = Component.text()
                .append(Component.text("[ALERT] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(incident.classification().name() + " at [" + x + ", " + y + ", " + z + "] by " + incident.actorName(), NamedTextColor.GOLD))
                .build();

        // 2. Interactive Clickable Chat Card
        Component teleportBtn = Component.text("[Teleport]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(String.format(Locale.ROOT, "/tp %d %d %d", x, y, z)))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to [" + x + ", " + y + ", " + z + "]", NamedTextColor.GRAY)));

        Component inspectBtn = Component.text("[Inspect]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(String.format(Locale.ROOT, "/chestlog inspect %d %d %d", x, y, z)))
                .hoverEvent(HoverEvent.showText(Component.text("Click to inspect container at [" + x + ", " + y + ", " + z + "]", NamedTextColor.GRAY)));

        Component trustBtn = Component.text("[Trust]", NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(String.format(Locale.ROOT, "/chestlog trust %s", incident.actorName())))
                .hoverEvent(HoverEvent.showText(Component.text("Click to trust " + incident.actorName() + " to allow container access", NamedTextColor.YELLOW)));

        Component chatComponent = Component.text()
                .append(Component.text("[ChestLogger] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(incident.classification().name() + ": ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(incident.summary(), NamedTextColor.YELLOW))
                .append(Component.space())
                .append(teleportBtn)
                .append(Component.space())
                .append(inspectBtn)
                .append(Component.space())
                .append(trustBtn)
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

    public ClaimManager getClaimManager() {
        return claimManager;
    }

    public void registerContainerOwner(long packedPos, UUID ownerUuid, String ownerName) {
        registerContainerOwner("world", packedPos, ownerUuid, ownerName);
    }

    public void registerContainerOwner(String dimension, long packedPos, UUID ownerUuid, String ownerName) {
        if (ownerUuid != null) {
            claimManager.claim(dimension != null ? dimension : "world", packedPos, ownerUuid, ownerName);
        }
    }

    private void resolveHistoricalOwnerIfAbsent(long packedPos, String dimension) {
        if (claimManager.isClaimed(dimension, packedPos)) {
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
                            claimManager.claim(dimension, packedPos, e.actorUuid(), e.actorName());
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
