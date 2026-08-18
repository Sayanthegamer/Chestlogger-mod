package com.chestlogger.paper;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.config.ConfigManager;
import com.chestlogger.web.WebConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 100% custom, zero-dependency 54-slot Paper Bukkit inventory GUI for in-game configuration.
 */
public final class PaperChestConfigView implements InventoryHolder {

    public static final int GUI_SIZE = 54;

    // Header Navigation Slots
    public static final int SLOT_TAB_ALERTS = 1;
    public static final int SLOT_TAB_TRACKED_ITEMS = 3;
    public static final int SLOT_TAB_GENERAL = 5;
    public static final int SLOT_TAB_WEB = 7;

    // Alerts Tab Action Slots
    public static final int SLOT_TOGGLE_ALERTS = 19;
    public static final int SLOT_DISCORD_WEBHOOK = 20;
    public static final int SLOT_BOT_USERNAME = 21;
    public static final int SLOT_COOLDOWN_DECREASE = 22;
    public static final int SLOT_COOLDOWN_INCREASE = 23;
    public static final int SLOT_TOGGLE_ACTION_BAR = 24;
    public static final int SLOT_TOGGLE_CHAT_ALERTS = 25;
    public static final int SLOT_DISTANCE_DECREASE = 30;
    public static final int SLOT_DISTANCE_INCREASE = 31;

    // Tracked Items Tab Action Slots
    public static final int TRACKED_ITEMS_START_SLOT = 10;
    public static final int TRACKED_ITEMS_END_SLOT = 34;
    public static final int SLOT_ADD_TRACKED_ITEM = 40;

    // General Tab Action Slots
    public static final int SLOT_GENERAL_AUTO_CLAIM = 20;
    public static final int SLOT_GENERAL_WAND = 24;

    // Web Tab Action Slots
    public static final int SLOT_TOGGLE_WEB = 20;
    public static final int SLOT_WEB_HOST = 22;
    public static final int SLOT_WEB_PORT = 24;
    public static final int SLOT_WEB_TOKEN = 25;

    // Footer Action Slots
    public static final int SLOT_SAVE = 49;
    public static final int SLOT_CLOSE = 45;

    public enum Tab {
        ALERTS,
        TRACKED_ITEMS,
        GENERAL,
        WEB
    }

    private final Player player;
    private final ConfigManager configManager;
    private Tab currentTab;

    // Local mutable state
    private boolean alertEnabled;
    private String discordWebhookUrl;
    private String botUsername;
    private String avatarUrl;
    private int alertCooldownSeconds;
    private boolean actionBarNoticeEnabled;
    private boolean inGameChatAlertEnabled;
    private int maxOwnerAlertDistance;
    private final List<String> trackedItems = new ArrayList<>();
    private boolean webEnabled;
    private String webHost;
    private int webPort;
    private String secretToken;

    private Inventory inventory;

    public PaperChestConfigView(Player player, ConfigManager configManager) {
        this(player, configManager, Tab.ALERTS);
    }

    public PaperChestConfigView(Player player, ConfigManager configManager, Tab initialTab) {
        this.player = Objects.requireNonNull(player, "player cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.currentTab = initialTab != null ? initialTab : Tab.ALERTS;

        AlertConfig alert = configManager.getAlertConfig();
        WebConfig web = configManager.getWebConfig();

        this.alertEnabled = alert != null && alert.enabled();
        this.discordWebhookUrl = alert != null && alert.webhookUrl() != null ? alert.webhookUrl() : "";
        this.botUsername = alert != null && alert.botUsername() != null ? alert.botUsername() : "ChestLogger Alerts";
        this.avatarUrl = alert != null && alert.avatarUrl() != null ? alert.avatarUrl() : "";
        this.alertCooldownSeconds = alert != null ? alert.rateLimitPerMinute() : 30;
        this.actionBarNoticeEnabled = configManager.isActionBarNoticeEnabled();
        this.inGameChatAlertEnabled = configManager.isInGameChatAlertEnabled();
        this.maxOwnerAlertDistance = configManager.getMaxOwnerAlertDistance();

        if (alert != null && alert.valuableItems() != null) {
            this.trackedItems.addAll(alert.valuableItems());
        }

        this.webEnabled = web != null && web.isEnabled();
        this.webHost = web != null && web.getHost() != null ? web.getHost() : "127.0.0.1";
        this.webPort = web != null ? web.getPort() : 8080;
        this.secretToken = web != null && web.getSecretToken() != null ? web.getSecretToken() : "";
    }

    public Player getPlayer() {
        return player;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public Tab getCurrentTab() {
        return currentTab;
    }

    public void setTab(Tab tab) {
        if (tab != null && this.currentTab != tab) {
            this.currentTab = tab;
            render();
        }
    }

    public boolean isAlertEnabled() {
        return alertEnabled;
    }

    public void toggleAlerts() {
        this.alertEnabled = !this.alertEnabled;
        render();
    }

    public boolean isActionBarNoticeEnabled() {
        return actionBarNoticeEnabled;
    }

    public void toggleActionBarNotice() {
        this.actionBarNoticeEnabled = !this.actionBarNoticeEnabled;
        render();
    }

    public boolean isInGameChatAlertEnabled() {
        return inGameChatAlertEnabled;
    }

    public void toggleChatAlerts() {
        this.inGameChatAlertEnabled = !this.inGameChatAlertEnabled;
        render();
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public void toggleWebEnabled() {
        this.webEnabled = !this.webEnabled;
        render();
    }

    public int getAlertCooldownSeconds() {
        return alertCooldownSeconds;
    }

    public void adjustCooldown(int delta) {
        this.alertCooldownSeconds = Math.max(5, Math.min(300, this.alertCooldownSeconds + delta));
        render();
    }

    public int getMaxOwnerAlertDistance() {
        return maxOwnerAlertDistance;
    }

    public void adjustDistance(int delta) {
        this.maxOwnerAlertDistance = Math.max(10, Math.min(500, this.maxOwnerAlertDistance + delta));
        render();
    }

    public List<String> getTrackedItems() {
        return Collections.unmodifiableList(trackedItems);
    }

    public void addTrackedItem(String item) {
        if (item == null || item.isBlank()) return;
        String trimmed = item.trim();
        if (!trackedItems.contains(trimmed)) {
            trackedItems.add(trimmed);
            render();
        }
    }

    public void removeTrackedItem(String item) {
        if (item == null) return;
        if (trackedItems.remove(item.trim())) {
            render();
        }
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public void setDiscordWebhookUrl(String url) {
        this.discordWebhookUrl = url != null ? url : "";
        render();
    }

    public String getBotUsername() {
        return botUsername;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername != null ? botUsername : "";
        render();
    }

    public String getWebHost() {
        return webHost;
    }

    public void setWebHost(String webHost) {
        this.webHost = webHost != null ? webHost : "127.0.0.1";
        render();
    }

    public int getWebPort() {
        return webPort;
    }

    public void setWebPort(int webPort) {
        this.webPort = webPort;
        render();
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, GUI_SIZE, "§8ChestLogger Config: " + currentTab.name());
        render();
        player.openInventory(this.inventory);
    }

    public void handleSlotClick(int slot) {
        // Tab Navigation
        if (slot == SLOT_TAB_ALERTS) {
            setTab(Tab.ALERTS);
            return;
        }
        if (slot == SLOT_TAB_TRACKED_ITEMS) {
            setTab(Tab.TRACKED_ITEMS);
            return;
        }
        if (slot == SLOT_TAB_GENERAL) {
            setTab(Tab.GENERAL);
            return;
        }
        if (slot == SLOT_TAB_WEB) {
            setTab(Tab.WEB);
            return;
        }

        // Global Footer
        if (slot == SLOT_SAVE) {
            save();
            player.closeInventory();
            player.sendMessage("§a[ChestLogger] Settings saved and hot-reloaded successfully!");
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Tab-specific interactions
        switch (currentTab) {
            case ALERTS -> handleAlertsTabClick(slot);
            case TRACKED_ITEMS -> handleTrackedItemsTabClick(slot);
            case GENERAL -> handleGeneralTabClick(slot);
            case WEB -> handleWebTabClick(slot);
        }
    }

    private void handleAlertsTabClick(int slot) {
        if (slot == SLOT_TOGGLE_ALERTS) {
            toggleAlerts();
        } else if (slot == SLOT_DISCORD_WEBHOOK) {
            PaperChatPromptManager.prompt(player, "Discord Webhook URL", this, (view, text) -> view.setDiscordWebhookUrl(text));
        } else if (slot == SLOT_BOT_USERNAME) {
            PaperChatPromptManager.prompt(player, "Bot Username", this, (view, text) -> view.setBotUsername(text));
        } else if (slot == SLOT_COOLDOWN_DECREASE) {
            adjustCooldown(-5);
        } else if (slot == SLOT_COOLDOWN_INCREASE) {
            adjustCooldown(5);
        } else if (slot == SLOT_TOGGLE_ACTION_BAR) {
            toggleActionBarNotice();
        } else if (slot == SLOT_TOGGLE_CHAT_ALERTS) {
            toggleChatAlerts();
        } else if (slot == SLOT_DISTANCE_DECREASE) {
            adjustDistance(-10);
        } else if (slot == SLOT_DISTANCE_INCREASE) {
            adjustDistance(10);
        }
    }

    private void handleTrackedItemsTabClick(int slot) {
        if (slot >= TRACKED_ITEMS_START_SLOT && slot <= TRACKED_ITEMS_END_SLOT) {
            int index = slot - TRACKED_ITEMS_START_SLOT;
            if (index >= 0 && index < trackedItems.size()) {
                String removed = trackedItems.get(index);
                removeTrackedItem(removed);
                player.sendMessage("§e[ChestLogger] Removed tracked item: " + removed);
            }
        } else if (slot == SLOT_ADD_TRACKED_ITEM) {
            PaperChatPromptManager.prompt(player, "Item ID (e.g. minecraft:netherite_ingot)", this, (view, text) -> view.addTrackedItem(text));
        }
    }

    private void handleGeneralTabClick(int slot) {
        // General tab info/toggles
    }

    private void handleWebTabClick(int slot) {
        if (slot == SLOT_TOGGLE_WEB) {
            toggleWebEnabled();
        } else if (slot == SLOT_WEB_HOST) {
            PaperChatPromptManager.prompt(player, "Web Host IP", this, (view, text) -> view.setWebHost(text));
        } else if (slot == SLOT_WEB_PORT) {
            PaperChatPromptManager.prompt(player, "Web Port", this, (view, text) -> {
                try {
                    view.setWebPort(Integer.parseInt(text.trim()));
                } catch (NumberFormatException ignored) {}
            });
        }
    }

    public void save() {
        AlertConfig current = configManager.getAlertConfig();
        AlertConfig updated = new AlertConfig(
                alertEnabled,
                discordWebhookUrl,
                botUsername,
                avatarUrl,
                current != null ? current.quantityThreshold() : 64,
                new HashSet<>(trackedItems),
                current != null ? current.alertOnContainerBreak() : true,
                current != null ? current.alertOnValuableTheft() : true,
                alertCooldownSeconds
        );

        configManager.updateAlertConfig(updated);
        configManager.setActionBarNoticeEnabled(actionBarNoticeEnabled);
        configManager.setInGameChatAlertEnabled(inGameChatAlertEnabled);
        configManager.setMaxOwnerAlertDistance(maxOwnerAlertDistance);
        configManager.setTrackedItems(new HashSet<>(trackedItems));

        configManager.updateWebConfig(web -> {
            web.setEnabled(webEnabled);
            web.setHost(webHost);
            web.setPort(webPort);
        });

        configManager.saveAll();
    }

    public void render() {
        if (this.inventory == null) return;
        this.inventory.clear();

        // Fill background borders
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, "§7", List.of());
        for (int i = 0; i < GUI_SIZE; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || (i + 1) % 9 == 0) {
                inventory.setItem(i, filler);
            }
        }

        // Header Navigation Tabs
        inventory.setItem(SLOT_TAB_ALERTS, createItem(
                currentTab == Tab.ALERTS ? Material.BELL : Material.GRAY_DYE,
                (currentTab == Tab.ALERTS ? "§e§n🚨 Alerts & Security§r" : "§7🚨 Alerts & Security"),
                List.of("§7Configure Discord webhooks, HUD notices, and rate limits.")
        ));

        inventory.setItem(SLOT_TAB_TRACKED_ITEMS, createItem(
                currentTab == Tab.TRACKED_ITEMS ? Material.DIAMOND : Material.GRAY_DYE,
                (currentTab == Tab.TRACKED_ITEMS ? "§e§n💎 Tracked Items§r" : "§7💎 Tracked Items"),
                List.of("§7Manage theft-monitored high value item IDs.")
        ));

        inventory.setItem(SLOT_TAB_GENERAL, createItem(
                currentTab == Tab.GENERAL ? Material.CHEST : Material.GRAY_DYE,
                (currentTab == Tab.GENERAL ? "§e§n⚙️ General & Claims§r" : "§7⚙️ General & Claims"),
                List.of("§7Configure auto-claim and inspect wand properties.")
        ));

        inventory.setItem(SLOT_TAB_WEB, createItem(
                currentTab == Tab.WEB ? Material.BEACON : Material.GRAY_DYE,
                (currentTab == Tab.WEB ? "§e§n🌐 Web Server§r" : "§7🌐 Web Server"),
                List.of("§7Configure embedded web server and authentication token.")
        ));

        // Render tab contents
        switch (currentTab) {
            case ALERTS -> renderAlertsTab();
            case TRACKED_ITEMS -> renderTrackedItemsTab();
            case GENERAL -> renderGeneralTab();
            case WEB -> renderWebTab();
        }

        // Footer Actions
        inventory.setItem(SLOT_SAVE, createItem(Material.EMERALD_BLOCK, "§a💾 Save & Apply", List.of("§7Save and hot-reload changes on server live.")));
        inventory.setItem(SLOT_CLOSE, createItem(Material.BARRIER, "§c❌ Cancel / Close", List.of("§7Discard changes and exit menu.")));
    }

    private void renderAlertsTab() {
        inventory.setItem(SLOT_TOGGLE_ALERTS, createItem(
                alertEnabled ? Material.LIME_DYE : Material.GRAY_DYE,
                alertEnabled ? "§aDiscord Alerts: ENABLED" : "§cDiscord Alerts: DISABLED",
                List.of("§7Click to toggle Discord webhook dispatching.")
        ));

        inventory.setItem(SLOT_DISCORD_WEBHOOK, createItem(
                Material.PAPER,
                "§6Discord Webhook URL",
                List.of("§7Current: §f" + (discordWebhookUrl.isEmpty() ? "None" : (discordWebhookUrl.length() > 30 ? discordWebhookUrl.substring(0, 30) + "..." : discordWebhookUrl)), "§eClick to edit via chat.")
        ));

        inventory.setItem(SLOT_BOT_USERNAME, createItem(
                Material.NAME_TAG,
                "§6Bot Username: §f" + botUsername,
                List.of("§eClick to edit via chat.")
        ));

        inventory.setItem(SLOT_COOLDOWN_DECREASE, createItem(Material.ARROW, "§c[-] Cooldown (-5s)", List.of("§7Current: " + alertCooldownSeconds + "s")));
        inventory.setItem(SLOT_COOLDOWN_INCREASE, createItem(Material.ARROW, "§a[+] Cooldown (+5s)", List.of("§7Current: " + alertCooldownSeconds + "s")));

        inventory.setItem(SLOT_TOGGLE_ACTION_BAR, createItem(
                actionBarNoticeEnabled ? Material.EXPERIENCE_BOTTLE : Material.GRAY_DYE,
                actionBarNoticeEnabled ? "§aAction-Bar HUD: ENABLED" : "§7Action-Bar HUD: DISABLED",
                List.of("§7Click to toggle action-bar notice HUD.")
        ));

        inventory.setItem(SLOT_TOGGLE_CHAT_ALERTS, createItem(
                inGameChatAlertEnabled ? Material.WRITABLE_BOOK : Material.GRAY_DYE,
                inGameChatAlertEnabled ? "§aChat Alert Cards: ENABLED" : "§7Chat Alert Cards: DISABLED",
                List.of("§7Click to toggle in-game chat security cards.")
        ));

        inventory.setItem(SLOT_DISTANCE_DECREASE, createItem(Material.ARROW, "§c[-] Owner Distance (-10m)", List.of("§7Current: " + maxOwnerAlertDistance + "m")));
        inventory.setItem(SLOT_DISTANCE_INCREASE, createItem(Material.ARROW, "§a[+] Owner Distance (+10m)", List.of("§7Current: " + maxOwnerAlertDistance + "m")));
    }

    private void renderTrackedItemsTab() {
        for (int i = 0; i < Math.min(25, trackedItems.size()); i++) {
            String itemId = trackedItems.get(i);
            Material mat = resolveMaterial(itemId);
            inventory.setItem(TRACKED_ITEMS_START_SLOT + i, createItem(
                    mat,
                    "§b" + itemId,
                    List.of("§cClick to remove from tracked items.")
            ));
        }

        inventory.setItem(SLOT_ADD_TRACKED_ITEM, createItem(
                Material.ANVIL,
                "§a+ Add Tracked Item",
                List.of("§7Click to prompt chat input for a new item ID.")
        ));
    }

    private void renderGeneralTab() {
        inventory.setItem(SLOT_GENERAL_AUTO_CLAIM, createItem(
                Material.LIME_DYE,
                "§aAuto-Claim on Place: ENABLED",
                List.of("§7Automatically claims containers upon placement.")
        ));

        inventory.setItem(SLOT_GENERAL_WAND, createItem(
                Material.STICK,
                "§6Wand Tool: minecraft:stick",
                List.of("§7Inspection wand tool item.")
        ));
    }

    private void renderWebTab() {
        inventory.setItem(SLOT_TOGGLE_WEB, createItem(
                webEnabled ? Material.LIME_DYE : Material.GRAY_DYE,
                webEnabled ? "§aWeb Dashboard: ENABLED" : "§cWeb Dashboard: DISABLED",
                List.of("§7Click to toggle embedded web server.")
        ));

        inventory.setItem(SLOT_WEB_HOST, createItem(
                Material.PAPER,
                "§6Web Host: §f" + webHost,
                List.of("§eClick to edit via chat.")
        ));

        inventory.setItem(SLOT_WEB_PORT, createItem(
                Material.REPEATER,
                "§6Web Port: §f" + webPort,
                List.of("§eClick to edit via chat.")
        ));

        inventory.setItem(SLOT_WEB_TOKEN, createItem(
                Material.TRIPWIRE_HOOK,
                "§eSecret Token: " + (secretToken.length() > 10 ? secretToken.substring(0, 8) + "..." : secretToken),
                List.of("§7Full Token: " + secretToken)
        ));
    }

    private static Material resolveMaterial(String itemId) {
        if (itemId == null) return Material.DIAMOND;
        String clean = itemId.startsWith("minecraft:") ? itemId.substring(10) : itemId;
        Material mat = Material.matchMaterial(clean.toUpperCase());
        return mat != null ? mat : Material.CHEST;
    }

    private static ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
