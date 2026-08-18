package com.chestlogger.paper;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.inspect.InspectModeManager;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.TransactionFormatter;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackResult;
import com.chestlogger.web.EmbeddedHttpServer;
import com.chestlogger.web.WebConfig;
import com.chestlogger.security.TrustManager;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles Bukkit /chestlog command executions and tab completions.
 */
public final class PaperCommandExecutor implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final QueryEngine queryEngine;
    private final PersistentIndexManager indexManager;
    private final TransactionEventQueue eventQueue;
    private final PaperRollbackExecutor rollbackExecutor;
    private final EmbeddedHttpServer webServer;
    private final InspectModeManager inspectModeManager;
    private final TrustManager trustManager;

    public PaperCommandExecutor(
            Plugin plugin,
            QueryEngine queryEngine,
            PersistentIndexManager indexManager,
            TransactionEventQueue eventQueue,
            PaperRollbackExecutor rollbackExecutor,
            EmbeddedHttpServer webServer,
            InspectModeManager inspectModeManager,
            TrustManager trustManager
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine cannot be null");
        this.indexManager = Objects.requireNonNull(indexManager, "indexManager cannot be null");
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue cannot be null");
        this.rollbackExecutor = Objects.requireNonNull(rollbackExecutor, "rollbackExecutor cannot be null");
        this.webServer = webServer;
        this.inspectModeManager = Objects.requireNonNull(inspectModeManager, "inspectModeManager cannot be null");
        this.trustManager = trustManager != null ? trustManager : new TrustManager();
    }

    public PaperCommandExecutor(
            Plugin plugin,
            QueryEngine queryEngine,
            PersistentIndexManager indexManager,
            TransactionEventQueue eventQueue,
            PaperRollbackExecutor rollbackExecutor,
            EmbeddedHttpServer webServer,
            InspectModeManager inspectModeManager
    ) {
        this(plugin, queryEngine, indexManager, eventQueue, rollbackExecutor, webServer, inspectModeManager, new TrustManager());
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    public boolean isInspecting(UUID playerUuid) {
        return inspectModeManager.isInspectActive(playerUuid);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && (player.hasPermission("chestlogger.inspect") || player.hasPermission("chestlogger.admin"))) {
                boolean active = inspectModeManager.toggleInspect(player.getUniqueId());
                if (active) {
                    player.sendMessage(ChatColor.GREEN + "[ChestLogger] Inspect mode enabled. Left-click a container to inspect chat, right-click to inspect GUI.");
                } else {
                    player.sendMessage(ChatColor.YELLOW + "[ChestLogger] Inspect mode disabled.");
                }
                return true;
            }
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "i", "inspect" -> handleInspect(sender, args);
            case "wand" -> handleWand(sender);
            case "claim" -> handleClaim(sender, args);
            case "unclaim" -> handleUnclaim(sender);
            case "rb", "rollback" -> handleRollback(sender, args);
            case "stats" -> handleStats(sender);
            case "web" -> handleWeb(sender, args);
            case "config", "settings" -> handleConfig(sender, args);
            case "t", "trace" -> handleTrace(sender, args);
            case "trust" -> handleTrust(sender, args);
            case "untrust" -> handleUntrust(sender, args);
            case "trustlist" -> handleTrustList(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleInspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.inspect") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use inspect.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Inspect command with raycasting is only available to in-game players.");
            return;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !(targetBlock.getState() instanceof Container container)) {
            // Toggle inspection mode
            UUID uuid = player.getUniqueId();
            boolean active = inspectModeManager.toggleInspect(uuid);
            if (active) {
                player.sendMessage(ChatColor.GREEN + "[ChestLogger] Inspection mode enabled. Left-click container for chat, right-click for GUI.");
            } else {
                player.sendMessage(ChatColor.YELLOW + "[ChestLogger] Inspection mode disabled.");
            }
            return;
        }

        Location loc = targetBlock.getLocation();
        long packedPos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String dim = loc.getWorld() != null ? loc.getWorld().getName() : "minecraft:overworld";

        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {}
        }

        int finalPage = page;
        sender.sendMessage(ChatColor.GRAY + "[ChestLogger] Fetching records for container at (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                IndexQueryFilter filter = IndexQueryFilter.builder()
                        .exactBlockPos(packedPos)
                        .dimension(dim)
                        .limit(100)
                        .build();
                PagedResult<TransactionLogEntry> result = queryEngine.queryPaged(filter, finalPage, 10);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(ChatColor.GOLD + "=== ChestLogger Inspect: (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ") Page " + result.pageNumber() + "/" + result.totalPages() + " ===");
                    if (result.items().isEmpty()) {
                        sender.sendMessage(ChatColor.GRAY + "No transaction records found.");
                    } else {
                        for (TransactionLogEntry entry : result.items()) {
                            sender.sendMessage(ChatColor.WHITE + TransactionFormatter.formatLine(entry));
                        }
                    }
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "[ChestLogger] Query failed: " + e.getMessage())
                );
            }
        });
    }

    private void handleRollback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.rollback") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to execute rollbacks.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Rollback command must currently be targeted by an in-game player.");
            return;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !(targetBlock.getState() instanceof Container container)) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You must look at a valid container within 5 blocks to rollback.");
            return;
        }

        Location loc = targetBlock.getLocation();
        long packedPos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String dim = loc.getWorld() != null ? loc.getWorld().getName() : "minecraft:overworld";

        long sinceSeconds = 3600L; // Default 1 hour
        if (args.length > 1) {
            try {
                sinceSeconds = Long.parseLong(args[1]);
            } catch (NumberFormatException ignored) {}
        }

        long minTime = System.currentTimeMillis() - (sinceSeconds * 1000L);
        player.sendMessage(ChatColor.YELLOW + "[ChestLogger] Planning rollback for container at (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ") over past " + sinceSeconds + "s...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                IndexQueryFilter filter = IndexQueryFilter.builder()
                        .exactBlockPos(packedPos)
                        .dimension(dim)
                        .timeRange(minTime, Long.MAX_VALUE)
                        .limit(500)
                        .build();
                List<TransactionLogEntry> history = queryEngine.fetchRecords(filter);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    RollbackPlan plan = rollbackExecutor.plan(history, container.getInventory());
                    if (plan.steps().isEmpty()) {
                        player.sendMessage(ChatColor.YELLOW + "[ChestLogger] No eligible modifications found to rollback.");
                        return;
                    }

                    RollbackResult result = rollbackExecutor.execute(
                            plan,
                            container.getInventory(),
                            player.getUniqueId(),
                            player.getName(),
                            dim,
                            packedPos
                    );

                    player.sendMessage(ChatColor.GREEN + "[ChestLogger] Rollback complete! Applied " + result.appliedSteps() + " changes (" + result.conflictCount() + " conflicts).");
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "[ChestLogger] Rollback failed: " + e.getMessage())
                );
            }
        });
    }

    private void handleStats(CommandSender sender) {
        if (!sender.hasPermission("chestlogger.stats") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to view stats.");
            return;
        }

        int depth = eventQueue.getDepth();
        int capacity = eventQueue.getCapacity();
        long enqueued = eventQueue.getEnqueuedCount();
        long dropped = eventQueue.getDroppedCount();
        long drained = eventQueue.getDrainedCount();
        int indexSize = indexManager.size();

        sender.sendMessage(ChatColor.GOLD + "=== ChestLogger Telemetry ===");
        sender.sendMessage(ChatColor.YELLOW + "Queue Depth: " + ChatColor.WHITE + depth + " / " + capacity);
        sender.sendMessage(ChatColor.YELLOW + "Total Enqueued: " + ChatColor.WHITE + enqueued);
        sender.sendMessage(ChatColor.YELLOW + "Total Drained: " + ChatColor.WHITE + drained);
        sender.sendMessage(ChatColor.YELLOW + "Dropped Events: " + (dropped > 0 ? ChatColor.RED : ChatColor.GREEN) + dropped);
        sender.sendMessage(ChatColor.YELLOW + "Indexed Records: " + ChatColor.WHITE + indexSize);
    }

    private void handleWeb(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.web") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage the web dashboard.");
            return;
        }

        if (webServer == null) {
            sender.sendMessage(ChatColor.RED + "[ChestLogger] Web server is not configured.");
            return;
        }

        WebConfig config = webServer.getConfig();
        if (args.length > 1) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("start".equals(action)) {
                if (webServer.isRunning()) {
                    sender.sendMessage(ChatColor.YELLOW + "[ChestLogger] Web server is already running.");
                } else {
                    config.setEnabled(true);
                    webServer.start();
                    sender.sendMessage(ChatColor.GREEN + "[ChestLogger] Web server started on http://" + config.getHost() + ":" + config.getPort());
                }
                return;
            } else if ("stop".equals(action)) {
                if (!webServer.isRunning()) {
                    sender.sendMessage(ChatColor.YELLOW + "[ChestLogger] Web server is not currently running.");
                } else {
                    webServer.stop();
                    sender.sendMessage(ChatColor.GREEN + "[ChestLogger] Web server stopped.");
                }
                return;
            }
        }

        sender.sendMessage(ChatColor.GOLD + "=== ChestLogger Web Admin ===");
        sender.sendMessage(ChatColor.YELLOW + "Status: " + (webServer.isRunning() ? ChatColor.GREEN + "RUNNING" : ChatColor.RED + "STOPPED"));
        sender.sendMessage(ChatColor.YELLOW + "URL: " + ChatColor.WHITE + "http://" + config.getHost() + ":" + config.getPort());
        sender.sendMessage(ChatColor.YELLOW + "Secret Token: " + ChatColor.AQUA + config.getSecretToken());
    }

    private void handleWand(CommandSender sender) {
        if (!sender.hasPermission("chestlogger.inspect") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use the inspection wand.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "=== [ChestLogger Wand Info] ===");
        sender.sendMessage(ChatColor.YELLOW + "Wand Item: " + ChatColor.AQUA + inspectModeManager.getConfig().getWandItem());
        sender.sendMessage(ChatColor.YELLOW + "Mode: " + ChatColor.WHITE + "Left-click container for Chat history, Right-click for GUI history.");
        sender.sendMessage(ChatColor.YELLOW + "Tip: " + ChatColor.GRAY + "Use /chestlog i to toggle click-inspection without holding the wand.");
    }

    private void handleTrace(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.inspect") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to trace items.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Trace command is only available to in-game players.");
            return;
        }

        if (args.length >= 4) {
            int x, y, z;
            try {
                x = Integer.parseInt(args[1]);
                y = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "[ChestLogger] Invalid coordinates. Usage: /chestlog trace <x> <y> <z> [slot]");
                return;
            }

            int slot = 0;
            if (args.length >= 5) {
                try {
                    slot = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "[ChestLogger] Invalid slot index: " + args[4]);
                    return;
                }
            }

            Block block = player.getWorld().getBlockAt(x, y, z);
            if (!(block.getState() instanceof Container container)) {
                player.sendMessage(ChatColor.RED + "[ChestLogger] Block at (" + x + ", " + y + ", " + z + ") is not a container.");
                return;
            }

            if (slot < 0 || slot >= container.getInventory().getSize()) {
                player.sendMessage(ChatColor.RED + "[ChestLogger] Invalid slot " + slot + " for container of size " + container.getInventory().getSize() + ".");
                return;
            }

            org.bukkit.inventory.ItemStack stack = container.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                player.sendMessage(ChatColor.RED + "[ChestLogger] Slot " + slot + " in container at (" + x + ", " + y + ", " + z + ") is empty.");
                return;
            }

            String itemId = PaperRollbackExecutor.resolveItemId(stack.getType());
            long packedPos = BlockPosUtil.pack(x, y, z);
            String dim = player.getWorld().getName();

            player.sendMessage(ChatColor.GRAY + "[ChestLogger] Tracing provenance for " + itemId + " at (" + x + ", " + y + ", " + z + ") slot " + slot + "...");

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    com.chestlogger.provenance.ItemProvenanceResolver resolver = new com.chestlogger.provenance.ItemProvenanceResolver();
                    com.chestlogger.provenance.ProvenanceGraph graph = resolver.resolveProvenance(packedPos, dim, itemId, queryEngine);

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (graph.isEmpty()) {
                            player.sendMessage(ChatColor.YELLOW + "[ChestLogger] No provenance history found for " + itemId + ".");
                            return;
                        }
                        PaperProvenanceGuiView view = new PaperProvenanceGuiView(player, graph);
                        view.open();
                    });
                } catch (Exception e) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.sendMessage(ChatColor.RED + "[ChestLogger] Trace resolution failed: " + e.getMessage())
                    );
                }
            });
            return;
        }

        // Trace main hand item
        org.bukkit.inventory.ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem == null || handItem.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You must hold an item in your main hand to trace it, or specify: /chestlog trace <x> <y> <z> [slot]");
            return;
        }

        String itemId = PaperRollbackExecutor.resolveItemId(handItem.getType());
        String dim = player.getWorld().getName();

        player.sendMessage(ChatColor.GRAY + "[ChestLogger] Tracing provenance for " + itemId + " in your main hand...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.chestlogger.provenance.ItemProvenanceResolver resolver = new com.chestlogger.provenance.ItemProvenanceResolver();
                com.chestlogger.provenance.ProvenanceGraph graph = resolver.resolveProvenance(0L, dim, itemId, queryEngine);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (graph.isEmpty()) {
                        player.sendMessage(ChatColor.YELLOW + "[ChestLogger] No provenance history found for " + itemId + ".");
                        return;
                    }
                    PaperProvenanceGuiView view = new PaperProvenanceGuiView(player, graph);
                    view.open();
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "[ChestLogger] Trace resolution failed: " + e.getMessage())
                );
            }
        });
    }

    private void handleTrust(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.trust") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage trust lists.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Trust commands can only be executed by in-game players.");
            return;
        }

        if (args.length < 2 || args[1].isBlank()) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] Usage: /chestlog trust <player>");
            return;
        }

        String targetName = args[1].trim();
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You cannot trust yourself!");
            return;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);
        String finalTargetName = resolvePlayerName(targetUuid, targetName);

        if (player.getUniqueId().equals(targetUuid)) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You cannot trust yourself!");
            return;
        }

        if (trustManager.isTrusted(player.getUniqueId(), targetUuid)) {
            player.sendMessage(ChatColor.YELLOW + "[ChestLogger] " + finalTargetName + " is already in your trust list.");
            return;
        }

        boolean added = trustManager.trust(player.getUniqueId(), targetUuid);
        if (added) {
            try {
                trustManager.save();
            } catch (Exception e) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("[ChestLogger] Failed to save trust_data.json: " + e.getMessage());
                }
            }
            player.sendMessage(ChatColor.GREEN + "[ChestLogger] Successfully trusted " + finalTargetName + ". They can now access your containers without triggering alerts.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "[ChestLogger] " + finalTargetName + " is already in your trust list.");
        }
    }

    private void handleUntrust(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.trust") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage trust lists.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Trust commands can only be executed by in-game players.");
            return;
        }

        if (args.length < 2 || args[1].isBlank()) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] Usage: /chestlog untrust <player>");
            return;
        }

        String targetName = args[1].trim();
        UUID targetUuid = null;
        String finalTargetName = targetName;

        // Check already trusted players first to match exact UUID
        Set<UUID> trusted = trustManager.getTrustList(player.getUniqueId());
        for (UUID u : trusted) {
            String name = resolvePlayerName(u, null);
            if (name.equalsIgnoreCase(targetName)) {
                targetUuid = u;
                finalTargetName = name;
                break;
            }
        }

        if (targetUuid == null) {
            targetUuid = resolvePlayerUuid(targetName);
            finalTargetName = resolvePlayerName(targetUuid, targetName);
        }

        boolean removed = trustManager.untrust(player.getUniqueId(), targetUuid);
        if (removed) {
            try {
                trustManager.save();
            } catch (Exception e) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("[ChestLogger] Failed to save trust_data.json: " + e.getMessage());
                }
            }
            player.sendMessage(ChatColor.GREEN + "[ChestLogger] Successfully untrusted " + finalTargetName + ".");
        } else {
            player.sendMessage(ChatColor.YELLOW + "[ChestLogger] " + finalTargetName + " is not in your trust list.");
        }
    }

    private void handleTrustList(CommandSender sender) {
        if (!sender.hasPermission("chestlogger.trust") && !sender.hasPermission("chestlogger.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage trust lists.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Trust commands can only be executed by in-game players.");
            return;
        }

        Set<UUID> trusted = trustManager.getTrustList(player.getUniqueId());
        if (trusted.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "[ChestLogger] You have not trusted any players yet. Use /chestlog trust <player> to add someone.");
            return;
        }

        List<String> names = new ArrayList<>();
        for (UUID uuid : trusted) {
            names.add(resolvePlayerName(uuid, null));
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        player.sendMessage(ChatColor.GOLD + "=== Trusted Players (" + trusted.size() + ") ===");
        player.sendMessage(ChatColor.WHITE + String.join(", ", names));
    }

    private UUID resolvePlayerUuid(String name) {
        try {
            if (org.bukkit.Bukkit.getServer() != null) {
                Player online = org.bukkit.Bukkit.getPlayerExact(name);
                if (online != null) {
                    return online.getUniqueId();
                }
                org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(name);
                if (offline.hasPlayedBefore() || offline.isOnline() || offline.getName() != null) {
                    return offline.getUniqueId();
                }
            }
        } catch (Exception ignored) {}
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    private String resolvePlayerName(UUID uuid, String defaultFallback) {
        try {
            if (org.bukkit.Bukkit.getServer() != null) {
                Player online = org.bukkit.Bukkit.getPlayer(uuid);
                if (online != null) {
                    return online.getName();
                }
                org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(uuid);
                if (offline.getName() != null) {
                    return offline.getName();
                }
            }
        } catch (Exception ignored) {}
        return defaultFallback != null ? defaultFallback : uuid.toString();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ChestLogger Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog [i|inspect] [page]" + ChatColor.WHITE + " - Toggle inspect mode or inspect targeted container");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog wand" + ChatColor.WHITE + " - View inspection wand tool details");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog claim [radius]" + ChatColor.WHITE + " - Claim container or radius of containers");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog unclaim" + ChatColor.WHITE + " - Remove claim on targeted container");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog trace <x> <y> <z> [slot]" + ChatColor.WHITE + " - Trace item provenance at container slot");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog trace [hand]" + ChatColor.WHITE + " - Trace item provenance for item in hand");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog trust <player>" + ChatColor.WHITE + " - Trust a player to access your containers");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog untrust <player>" + ChatColor.WHITE + " - Revoke trust from a player");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog trustlist" + ChatColor.WHITE + " - View your list of trusted players");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog rollback [seconds]" + ChatColor.WHITE + " - Revert container changes");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog stats" + ChatColor.WHITE + " - View queue and memory telemetry");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog web [start|stop]" + ChatColor.WHITE + " - Manage web dashboard");
    }

    private void handleClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be executed by a player.");
            return;
        }

        com.chestlogger.claim.ClaimManager claimManager = null;
        if (plugin instanceof ChestLoggerPlugin clp) {
            claimManager = clp.getClaimManager();
        }
        if (claimManager == null) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] Claim manager is not available.");
            return;
        }

        // Check if radius claim is requested: /chestlog claim <radius>
        if (args.length > 1 && player.hasPermission("chestlogger.admin")) {
            try {
                int radius = Integer.parseInt(args[1]);
                if (radius < 1 || radius > 32) {
                    player.sendMessage(ChatColor.RED + "Radius must be between 1 and 32 blocks.");
                    return;
                }
                int claimedCount = 0;
                Location center = player.getLocation();
                org.bukkit.World world = center.getWorld();
                int cx = center.getBlockX();
                int cy = center.getBlockY();
                int cz = center.getBlockZ();
                String dimension = world != null ? world.getName() : "world";

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                                org.bukkit.block.Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                                if (isContainerBlock(b)) {
                                    long p = BlockPosUtil.pack(cx + dx, cy + dy, cz + dz);
                                    claimManager.claim(dimension, p, player.getUniqueId(), player.getName());
                                    claimedCount++;
                                }
                            }
                        }
                    }
                }
                player.sendMessage(ChatColor.GREEN + String.format(Locale.ROOT, "[ChestLogger] Batch claimed %d container(s) within %d blocks.", claimedCount, radius));
                return;
            } catch (NumberFormatException ignored) {}
        }

        // Single targeted container raycast
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null || !isContainerBlock(target)) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You must be looking at a container (chest, barrel, shulker box) to claim it.");
            return;
        }

        String dimension = target.getWorld() != null ? target.getWorld().getName() : "world";
        long packed = BlockPosUtil.pack(target.getX(), target.getY(), target.getZ());

        UUID existingOwner = claimManager.getOwner(dimension, packed);
        String existingOwnerName = claimManager.getOwnerName(dimension, packed);
        if (existingOwner != null && !existingOwner.equals(player.getUniqueId()) && !player.hasPermission("chestlogger.admin")) {
            player.sendMessage(ChatColor.RED + String.format(Locale.ROOT, "[ChestLogger] Container is already claimed by %s!",
                    existingOwnerName != null ? existingOwnerName : "another player"));
            return;
        }

        // Check for double chest partner
        Long partnerPos = findDoubleChestPartner(target);
        if (partnerPos != null) {
            claimManager.claim(dimension, packed, partnerPos, player.getUniqueId(), player.getName());
            player.sendMessage(ChatColor.GREEN + String.format(Locale.ROOT, "[ChestLogger] Double chest at [%d, %d, %d] successfully claimed!",
                    target.getX(), target.getY(), target.getZ()));
        } else {
            claimManager.claim(dimension, packed, player.getUniqueId(), player.getName());
            player.sendMessage(ChatColor.GREEN + String.format(Locale.ROOT, "[ChestLogger] Container at [%d, %d, %d] successfully claimed!",
                    target.getX(), target.getY(), target.getZ()));
        }
    }

    private void handleUnclaim(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be executed by a player.");
            return;
        }

        com.chestlogger.claim.ClaimManager claimManager = null;
        if (plugin instanceof ChestLoggerPlugin clp) {
            claimManager = clp.getClaimManager();
        }
        if (claimManager == null) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] Claim manager is not available.");
            return;
        }

        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You must be looking at a container to unclaim it.");
            return;
        }

        String dimension = target.getWorld() != null ? target.getWorld().getName() : "world";
        long packed = BlockPosUtil.pack(target.getX(), target.getY(), target.getZ());

        if (!claimManager.isClaimed(dimension, packed)) {
            player.sendMessage(ChatColor.YELLOW + "[ChestLogger] This container is not claimed.");
            return;
        }

        UUID existingOwner = claimManager.getOwner(dimension, packed);
        if (existingOwner != null && !existingOwner.equals(player.getUniqueId()) && !player.hasPermission("chestlogger.admin")) {
            player.sendMessage(ChatColor.RED + "[ChestLogger] You do not own this container!");
            return;
        }

        claimManager.unclaim(dimension, packed);
        player.sendMessage(ChatColor.GREEN + String.format(Locale.ROOT, "[ChestLogger] Container claim removed from [%d, %d, %d].",
                target.getX(), target.getY(), target.getZ()));
    }

    public static boolean isContainerBlock(org.bukkit.block.Block b) {
        if (b == null) return false;
        org.bukkit.Material mat = b.getType();
        return mat == org.bukkit.Material.CHEST
                || mat == org.bukkit.Material.TRAPPED_CHEST
                || mat == org.bukkit.Material.BARREL
                || mat.name().endsWith("_SHULKER_BOX")
                || mat == org.bukkit.Material.SHULKER_BOX;
    }

    public static Long findDoubleChestPartner(org.bukkit.block.Block b) {
        if (b == null || (b.getType() != org.bukkit.Material.CHEST && b.getType() != org.bukkit.Material.TRAPPED_CHEST)) {
            return null;
        }
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData) {
            if (chestData.getType() == org.bukkit.block.data.type.Chest.Type.LEFT || chestData.getType() == org.bukkit.block.data.type.Chest.Type.RIGHT) {
                org.bukkit.block.BlockFace facing = chestData.getFacing();
                org.bukkit.block.BlockFace partnerFace = (chestData.getType() == org.bukkit.block.data.type.Chest.Type.LEFT)
                        ? getRightSideFace(facing) : getLeftSideFace(facing);
                org.bukkit.block.Block partner = b.getRelative(partnerFace);
                if (partner.getType() == b.getType()) {
                    return BlockPosUtil.pack(partner.getX(), partner.getY(), partner.getZ());
                }
            }
        }
        return null;
    }

    private static org.bukkit.block.BlockFace getLeftSideFace(org.bukkit.block.BlockFace facing) {
        return switch (facing) {
            case NORTH -> org.bukkit.block.BlockFace.WEST;
            case SOUTH -> org.bukkit.block.BlockFace.EAST;
            case WEST -> org.bukkit.block.BlockFace.SOUTH;
            case EAST -> org.bukkit.block.BlockFace.NORTH;
            default -> org.bukkit.block.BlockFace.SELF;
        };
    }

    private static org.bukkit.block.BlockFace getRightSideFace(org.bukkit.block.BlockFace facing) {
        return switch (facing) {
            case NORTH -> org.bukkit.block.BlockFace.EAST;
            case SOUTH -> org.bukkit.block.BlockFace.WEST;
            case WEST -> org.bukkit.block.BlockFace.NORTH;
            case EAST -> org.bukkit.block.BlockFace.SOUTH;
            default -> org.bukkit.block.BlockFace.SELF;
        };
    }

    private void handleConfig(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestlogger.admin") && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to configure ChestLogger.");
            return;
        }

        com.chestlogger.config.ConfigManager configManager = null;
        if (plugin instanceof ChestLoggerPlugin clPlugin) {
            configManager = clPlugin.getConfigManager();
        }

        if (configManager == null) {
            sender.sendMessage(ChatColor.RED + "[ChestLogger] ConfigManager is not initialized.");
            return;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "GUI configuration is only available to in-game players. Use /chestlog config <reload|get|set> from console.");
                return;
            }
            new PaperChestConfigView(player, configManager).open();
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                configManager.reloadFromDisk();
                sender.sendMessage(ChatColor.GREEN + "[ChestLogger] Configurations reloaded and hot-swapped from disk successfully.");
            }
            case "get" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /chestlog config get <key>");
                    return;
                }
                String key = args[2].toLowerCase(Locale.ROOT);
                com.chestlogger.alert.AlertConfig alert = configManager.getAlertConfig();
                com.chestlogger.web.WebConfig web = configManager.getWebConfig();
                String val = switch (key) {
                    case "alert_enabled", "alertenabled" -> String.valueOf(alert != null && alert.enabled());
                    case "webhook", "webhook_url", "webhookurl" -> alert != null ? alert.webhookUrl() : "";
                    case "bot_username", "botusername" -> alert != null ? alert.botUsername() : "";
                    case "cooldown", "cooldown_seconds" -> String.valueOf(alert != null ? alert.rateLimitPerMinute() : 30);
                    case "hud", "actionbar" -> String.valueOf(configManager.isActionBarNoticeEnabled());
                    case "chat", "chatalerts" -> String.valueOf(configManager.isInGameChatAlertEnabled());
                    case "owner_distance", "distance" -> String.valueOf(configManager.getMaxOwnerAlertDistance());
                    case "web_enabled", "webenabled" -> String.valueOf(web != null && web.isEnabled());
                    case "web_host", "webhost" -> web != null ? web.getHost() : "127.0.0.1";
                    case "web_port", "webport" -> String.valueOf(web != null ? web.getPort() : 8080);
                    default -> "Unknown setting key: " + args[2];
                };
                sender.sendMessage(ChatColor.YELLOW + "[ChestLogger] " + args[2] + " = " + ChatColor.WHITE + val);
            }
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /chestlog config set <key> <value>");
                    return;
                }
                String key = args[2].toLowerCase(Locale.ROOT);
                String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                try {
                    switch (key) {
                        case "alert_enabled", "alertenabled" -> {
                            boolean v = Boolean.parseBoolean(value.trim());
                            com.chestlogger.alert.AlertConfig current = configManager.getAlertConfig();
                            configManager.updateAlertConfig(new com.chestlogger.alert.AlertConfig(
                                    v,
                                    current != null ? current.webhookUrl() : "",
                                    current != null ? current.botUsername() : "",
                                    current != null ? current.avatarUrl() : "",
                                    current != null ? current.quantityThreshold() : 64,
                                    current != null ? current.valuableItems() : Set.of(),
                                    current != null ? current.alertOnContainerBreak() : true,
                                    current != null ? current.alertOnValuableTheft() : true,
                                    current != null ? current.rateLimitPerMinute() : 30
                            ));
                        }
                        case "webhook", "webhook_url", "webhookurl" -> configManager.setDiscordWebhookUrl(value.trim());
                        case "cooldown", "cooldown_seconds" -> configManager.setAlertCooldownSeconds(Integer.parseInt(value.trim()));
                        case "hud", "actionbar" -> configManager.setActionBarNoticeEnabled(Boolean.parseBoolean(value.trim()));
                        case "chat", "chatalerts" -> configManager.setInGameChatAlertEnabled(Boolean.parseBoolean(value.trim()));
                        case "owner_distance", "distance" -> configManager.setMaxOwnerAlertDistance(Integer.parseInt(value.trim()));
                        case "web_enabled", "webenabled" -> configManager.setWebEnabled(Boolean.parseBoolean(value.trim()));
                        case "web_port", "webport" -> configManager.setWebPort(Integer.parseInt(value.trim()));
                        default -> {
                            sender.sendMessage(ChatColor.RED + "[ChestLogger] Unknown setting key: " + args[2]);
                            return;
                        }
                    }
                    configManager.saveAll();
                    sender.sendMessage(ChatColor.GREEN + "[ChestLogger] Updated " + args[2] + " to " + value + " and hot-reloaded.");
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "[ChestLogger] Failed updating setting: " + e.getMessage());
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: /chestlog config [reload|get|set]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            List<String> subcommands = List.of("i", "inspect", "wand", "claim", "unclaim", "trace", "trust", "untrust", "trustlist", "rollback", "stats", "web", "config", "settings");
            return subcommands.stream().filter(s -> s.startsWith(current)).toList();
        }
        if (args.length == 2 && ("config".equalsIgnoreCase(args[0]) || "settings".equalsIgnoreCase(args[0]))) {
            String current = args[1].toLowerCase(Locale.ROOT);
            return List.of("reload", "get", "set").stream().filter(s -> s.startsWith(current)).toList();
        }
        if (args.length == 3 && ("config".equalsIgnoreCase(args[0]) || "settings".equalsIgnoreCase(args[0])) && ("get".equalsIgnoreCase(args[1]) || "set".equalsIgnoreCase(args[1]))) {
            String current = args[2].toLowerCase(Locale.ROOT);
            List<String> keys = List.of("alert_enabled", "webhook", "bot_username", "cooldown", "hud", "chat", "owner_distance", "web_enabled", "web_host", "web_port");
            return keys.stream().filter(s -> s.startsWith(current)).toList();
        }
        if (args.length == 2 && "web".equalsIgnoreCase(args[0])) {
            String current = args[1].toLowerCase(Locale.ROOT);
            return List.of("start", "stop").stream().filter(s -> s.startsWith(current)).toList();
        }
        if (args.length == 2 && "trace".equalsIgnoreCase(args[0])) {
            return List.of("hand");
        }
        if (args.length == 2 && "trust".equalsIgnoreCase(args[0])) {
            String current = args[1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            try {
                if (org.bukkit.Bukkit.getServer() != null) {
                    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (sender instanceof Player sp && sp.getUniqueId().equals(p.getUniqueId())) {
                            continue;
                        }
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(current)) {
                            suggestions.add(p.getName());
                        }
                    }
                }
            } catch (Exception ignored) {}
            return suggestions;
        }
        if (args.length == 2 && "untrust".equalsIgnoreCase(args[0])) {
            String current = args[1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            if (sender instanceof Player player) {
                Set<UUID> list = trustManager.getTrustList(player.getUniqueId());
                for (UUID u : list) {
                    String name = resolvePlayerName(u, null);
                    if (name.toLowerCase(Locale.ROOT).startsWith(current)) {
                        suggestions.add(name);
                    }
                }
            }
            return suggestions;
        }
        return Collections.emptyList();
    }
}
