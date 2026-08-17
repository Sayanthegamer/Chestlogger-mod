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

    public PaperCommandExecutor(
            Plugin plugin,
            QueryEngine queryEngine,
            PersistentIndexManager indexManager,
            TransactionEventQueue eventQueue,
            PaperRollbackExecutor rollbackExecutor,
            EmbeddedHttpServer webServer,
            InspectModeManager inspectModeManager
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine cannot be null");
        this.indexManager = Objects.requireNonNull(indexManager, "indexManager cannot be null");
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue cannot be null");
        this.rollbackExecutor = Objects.requireNonNull(rollbackExecutor, "rollbackExecutor cannot be null");
        this.webServer = webServer;
        this.inspectModeManager = Objects.requireNonNull(inspectModeManager, "inspectModeManager cannot be null");
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
            case "rb", "rollback" -> handleRollback(sender, args);
            case "stats" -> handleStats(sender);
            case "web" -> handleWeb(sender, args);
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

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ChestLogger Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog [i|inspect] [page]" + ChatColor.WHITE + " - Toggle inspect mode or inspect targeted container");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog wand" + ChatColor.WHITE + " - View inspection wand tool details");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog rollback [seconds]" + ChatColor.WHITE + " - Revert container changes");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog stats" + ChatColor.WHITE + " - View queue and memory telemetry");
        sender.sendMessage(ChatColor.YELLOW + "/chestlog web [start|stop]" + ChatColor.WHITE + " - Manage web dashboard");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("i", "inspect", "wand", "rollback", "stats", "web");
        }
        if (args.length == 2 && "web".equalsIgnoreCase(args[0])) {
            return List.of("start", "stop");
        }
        return Collections.emptyList();
    }
}
