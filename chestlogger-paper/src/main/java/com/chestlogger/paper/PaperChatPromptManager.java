package com.chestlogger.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Manages chat prompts for typing complex configuration values (e.g. Discord Webhooks, URLs)
 * with automatic timeout, cancel keywords, and GUI re-opening.
 */
public final class PaperChatPromptManager implements Listener {

    private record PromptSession(
            String promptName,
            PaperChestConfigView view,
            BiConsumer<PaperChestConfigView, String> callback,
            long expiresAt
    ) {}

    private static final Map<UUID, PromptSession> ACTIVE_PROMPTS = new ConcurrentHashMap<>();
    private static Plugin pluginInstance;

    public static void init(Plugin plugin) {
        pluginInstance = plugin;
    }

    public static void prompt(
            Player player,
            String promptName,
            PaperChestConfigView view,
            BiConsumer<PaperChestConfigView, String> callback
    ) {
        if (player == null || view == null || callback == null) return;

        ACTIVE_PROMPTS.put(player.getUniqueId(), new PromptSession(
                promptName,
                view,
                callback,
                System.currentTimeMillis() + 60000L // 60s timeout
        ));

        player.closeInventory();
        player.sendMessage("§e[ChestLogger] Editing §6" + promptName + "§e.");
        player.sendMessage("§ePlease type the new value in chat, or type §c'cancel'§e to abort.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PromptSession session = ACTIVE_PROMPTS.remove(player.getUniqueId());
        if (session == null) return;

        if (System.currentTimeMillis() > session.expiresAt()) {
            player.sendMessage("§c[ChestLogger] Edit session expired.");
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage().trim();

        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage("§c[ChestLogger] Edit cancelled.");
            reopenGui(player, session.view());
            return;
        }

        try {
            session.callback().accept(session.view(), message);
            player.sendMessage("§a[ChestLogger] Updated " + session.promptName() + "!");
        } catch (Exception e) {
            player.sendMessage("§c[ChestLogger] Failed to apply value: " + e.getMessage());
        }

        reopenGui(player, session.view());
    }

    private static void reopenGui(Player player, PaperChestConfigView view) {
        if (pluginInstance != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(pluginInstance, view::open);
        }
    }
}
