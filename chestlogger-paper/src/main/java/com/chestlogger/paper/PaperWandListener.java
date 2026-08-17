package com.chestlogger.paper;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.inspect.InspectModeManager;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.TransactionFormatter;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;

/**
 * Paper event listener handling interactive wand and click-to-inspect gestures.
 */
public final class PaperWandListener implements Listener {

    private final Plugin plugin;
    private final InspectModeManager inspectModeManager;
    private final QueryEngine queryEngine;
    private final PersistentIndexManager indexManager;

    public PaperWandListener(
            Plugin plugin,
            InspectModeManager inspectModeManager,
            QueryEngine queryEngine,
            PersistentIndexManager indexManager
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.inspectModeManager = Objects.requireNonNull(inspectModeManager, "inspectModeManager cannot be null");
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine cannot be null");
        this.indexManager = Objects.requireNonNull(indexManager, "indexManager cannot be null");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("chestlogger.inspect") && !player.hasPermission("chestlogger.admin")) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        String itemId = resolveItemId(item);

        if (!inspectModeManager.shouldInspect(player.getUniqueId(), itemId)) {
            return;
        }

        if (!(block.getState() instanceof Container)) {
            return;
        }

        // Cancel interaction to prevent opening or modifying container in inspect mode
        event.setCancelled(true);

        Location loc = block.getLocation();
        long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        if (!inspectModeManager.tryDebounce(player.getUniqueId(), pos)) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            performChatInspection(player, loc, block);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            // Right-click opens GUI or performs inspection
            performGuiOrChatInspection(player, loc, block);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Container)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("chestlogger.inspect") && !player.hasPermission("chestlogger.admin")) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        String itemId = resolveItemId(item);

        if (!inspectModeManager.shouldInspect(player.getUniqueId(), itemId)) {
            return;
        }

        event.setCancelled(true);
        Location loc = block.getLocation();
        long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        if (!inspectModeManager.tryDebounce(player.getUniqueId(), pos)) {
            return;
        }

        performChatInspection(player, loc, block);
    }

    private void performChatInspection(Player player, Location loc, Block block) {
        String dim = loc.getWorld() != null ? loc.getWorld().getName() : "minecraft:overworld";
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        long pos = BlockPosUtil.pack(x, y, z);

        player.sendMessage(ChatColor.GOLD + "=== [ChestLogger] Inspecting " + block.getType().name() +
                " at " + x + ", " + y + ", " + z + " ===");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                IndexQueryFilter filter = IndexQueryFilter.builder()
                        .exactBlockPos(pos)
                        .dimension(dim)
                        .limit(100)
                        .build();

                PagedResult<TransactionLogEntry> paged = queryEngine.queryPaged(filter, 1, 6);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (paged.items().isEmpty()) {
                        player.sendMessage(ChatColor.GRAY + "No container transaction records found for this position.");
                    } else {
                        for (TransactionLogEntry entry : paged.items()) {
                            player.sendMessage(ChatColor.WHITE + TransactionFormatter.formatLine(entry));
                        }
                    }
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "[ChestLogger] Query failed: " + e.getMessage())
                );
            }
        });
    }

    private void performGuiOrChatInspection(Player player, Location loc, Block block) {
        String dim = loc.getWorld() != null ? loc.getWorld().getName() : "minecraft:overworld";
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        long pos = BlockPosUtil.pack(x, y, z);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                IndexQueryFilter filter = IndexQueryFilter.builder()
                        .exactBlockPos(pos)
                        .dimension(dim)
                        .limit(500)
                        .build();

                List<TransactionLogEntry> matches = queryEngine.fetchRecords(filter);
                int capacity = 27;
                if (block.getState() instanceof org.bukkit.block.DoubleChest) {
                    capacity = 54;
                } else if (block.getState() instanceof Container c) {
                    capacity = c.getInventory().getSize();
                }

                int finalCapacity = capacity;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    PaperChestHistoryView view = new PaperChestHistoryView(
                            player,
                            matches,
                            block.getType().name(),
                            finalCapacity,
                            pos,
                            dim
                    );
                    view.open();
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "[ChestLogger] GUI load failed: " + e.getMessage())
                );
            }
        });
    }

    public static String resolveItemId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "minecraft:air";
        return "minecraft:" + item.getType().name().toLowerCase();
    }
}
