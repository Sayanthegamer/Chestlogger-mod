package com.chestlogger.client.gui;

import com.chestlogger.network.ChestLogConfigPayload;
import com.chestlogger.network.ChestLogConfigUpdatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 100% custom, zero-dependency in-game configuration screen for ChestLogger (Minecraft 26.2).
 */
public class ChestLogConfigScreen extends Screen {

    public enum Tab {
        ALERTS,
        TRACKED_ITEMS,
        GENERAL,
        WEB
    }

    private final ChestLogConfigPayload initialPayload;
    private Tab currentTab = Tab.ALERTS;

    // Config state
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

    // UI Input Widgets
    private EditBox webhookUrlBox;
    private EditBox botUsernameBox;
    private EditBox addItemBox;
    private EditBox webHostBox;
    private EditBox webPortBox;

    // Tab buttons
    private Button btnTabAlerts;
    private Button btnTabItems;
    private Button btnTabGeneral;
    private Button btnTabWeb;

    // Action buttons
    private Button btnSave;
    private Button btnCancel;

    public ChestLogConfigScreen(ChestLogConfigPayload payload) {
        super(Component.literal("ChestLogger Configuration"));
        this.initialPayload = payload;

        this.alertEnabled = payload.alertEnabled();
        this.discordWebhookUrl = payload.discordWebhookUrl();
        this.botUsername = payload.botUsername();
        this.avatarUrl = payload.avatarUrl();
        this.alertCooldownSeconds = payload.alertCooldownSeconds();
        this.actionBarNoticeEnabled = payload.actionBarNoticeEnabled();
        this.inGameChatAlertEnabled = payload.inGameChatAlertEnabled();
        this.maxOwnerAlertDistance = payload.maxOwnerAlertDistance();
        this.trackedItems.addAll(payload.trackedItems());
        this.webEnabled = payload.webEnabled();
        this.webHost = payload.webHost();
        this.webPort = payload.webPort();
        this.secretToken = payload.secretToken();
    }

    @Override
    protected void init() {
        super.init();
        rebuildConfigWidgets();
    }

    private void syncInputsToState() {
        if (webhookUrlBox != null) discordWebhookUrl = webhookUrlBox.getValue().trim();
        if (botUsernameBox != null) botUsername = botUsernameBox.getValue().trim();
        if (webHostBox != null) webHost = webHostBox.getValue().trim();
        if (webPortBox != null) {
            try {
                webPort = Integer.parseInt(webPortBox.getValue().trim());
            } catch (NumberFormatException ignored) {}
        }
    }

    private void rebuildConfigWidgets() {
        syncInputsToState();
        clearWidgets();

        int centerX = width / 2;
        int topY = 28;

        // Tab Navigation
        int tabWidth = 90;
        int tabY = topY;
        int tabStartX = centerX - (tabWidth * 4) / 2;

        btnTabAlerts = addRenderableWidget(Button.builder(
                Component.literal(currentTab == Tab.ALERTS ? "§e§n🚨 Alerts§r" : "🚨 Alerts"),
                btn -> { currentTab = Tab.ALERTS; rebuildConfigWidgets(); })
                .bounds(tabStartX, tabY, tabWidth, 20).build());

        btnTabItems = addRenderableWidget(Button.builder(
                Component.literal(currentTab == Tab.TRACKED_ITEMS ? "§e§n💎 Items§r" : "💎 Items"),
                btn -> { currentTab = Tab.TRACKED_ITEMS; rebuildConfigWidgets(); })
                .bounds(tabStartX + tabWidth, tabY, tabWidth, 20).build());

        btnTabGeneral = addRenderableWidget(Button.builder(
                Component.literal(currentTab == Tab.GENERAL ? "§e§n⚙️ General§r" : "⚙️ General"),
                btn -> { currentTab = Tab.GENERAL; rebuildConfigWidgets(); })
                .bounds(tabStartX + tabWidth * 2, tabY, tabWidth, 20).build());

        btnTabWeb = addRenderableWidget(Button.builder(
                Component.literal(currentTab == Tab.WEB ? "§e§n🌐 Web§r" : "🌐 Web"),
                btn -> { currentTab = Tab.WEB; rebuildConfigWidgets(); })
                .bounds(tabStartX + tabWidth * 3, tabY, tabWidth, 20).build());

        // Content Area
        int contentY = topY + 28;

        switch (currentTab) {
            case ALERTS -> initAlertsTab(centerX, contentY);
            case TRACKED_ITEMS -> initTrackedItemsTab(centerX, contentY);
            case GENERAL -> initGeneralTab(centerX, contentY);
            case WEB -> initWebTab(centerX, contentY);
        }

        // Footer Actions
        int footerY = height - 28;
        btnSave = addRenderableWidget(Button.builder(
                Component.literal("§a💾 Save & Apply"),
                btn -> saveAndApply())
                .bounds(centerX - 110, footerY, 100, 20)
                .tooltip(Tooltip.create(Component.literal("Save changes and hot-reload live on server")))
                .build());

        btnCancel = addRenderableWidget(Button.builder(
                Component.literal("§cCancel"),
                btn -> onClose())
                .bounds(centerX + 10, footerY, 100, 20)
                .build());
    }

    private void initAlertsTab(int centerX, int startY) {
        int labelLeft = centerX - 180;
        int inputLeft = centerX - 50;

        // 1. Discord Alerts Enabled Toggle
        addRenderableWidget(Button.builder(
                Component.literal(alertEnabled ? "§aDiscord Alerts: ENABLED" : "§cDiscord Alerts: DISABLED"),
                btn -> { alertEnabled = !alertEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY, 230, 20).build());

        // 2. Webhook URL
        webhookUrlBox = new EditBox(font, inputLeft, startY + 26, 230, 18, Component.literal("Discord Webhook URL"));
        webhookUrlBox.setMaxLength(512);
        webhookUrlBox.setValue(discordWebhookUrl);
        webhookUrlBox.setHint(Component.literal("§8https://discord.com/api/webhooks/..."));
        addRenderableWidget(webhookUrlBox);

        // 3. Bot Username
        botUsernameBox = new EditBox(font, inputLeft, startY + 50, 230, 18, Component.literal("Bot Username"));
        botUsernameBox.setMaxLength(64);
        botUsernameBox.setValue(botUsername);
        botUsernameBox.setHint(Component.literal("§8ChestLogger Alerts"));
        addRenderableWidget(botUsernameBox);

        // 4. Cooldown Stepper
        addRenderableWidget(Button.builder(Component.literal("[-]"), btn -> {
            alertCooldownSeconds = Math.max(5, alertCooldownSeconds - 5);
            rebuildConfigWidgets();
        }).bounds(inputLeft, startY + 74, 30, 20).build());

        addRenderableWidget(Button.builder(Component.literal(alertCooldownSeconds + "s Cooldown"), btn -> {})
                .bounds(inputLeft + 34, startY + 74, 162, 20).build());

        addRenderableWidget(Button.builder(Component.literal("[+]"), btn -> {
            alertCooldownSeconds = Math.min(300, alertCooldownSeconds + 5);
            rebuildConfigWidgets();
        }).bounds(inputLeft + 200, startY + 74, 30, 20).build());

        // 5. In-Game HUD Notice Toggle
        addRenderableWidget(Button.builder(
                Component.literal(actionBarNoticeEnabled ? "§aAction-Bar HUD: ENABLED" : "§7Action-Bar HUD: DISABLED"),
                btn -> { actionBarNoticeEnabled = !actionBarNoticeEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY + 98, 230, 20).build());

        // 6. In-Game Chat Card Toggle
        addRenderableWidget(Button.builder(
                Component.literal(inGameChatAlertEnabled ? "§aChat Alert Cards: ENABLED" : "§7Chat Alert Cards: DISABLED"),
                btn -> { inGameChatAlertEnabled = !inGameChatAlertEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY + 122, 230, 20).build());

        // 7. Max Owner Distance Stepper
        addRenderableWidget(Button.builder(Component.literal("[-]"), btn -> {
            maxOwnerAlertDistance = Math.max(10, maxOwnerAlertDistance - 10);
            rebuildConfigWidgets();
        }).bounds(inputLeft, startY + 146, 30, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Owner Distance: " + maxOwnerAlertDistance + "m"), btn -> {})
                .bounds(inputLeft + 34, startY + 146, 162, 20).build());

        addRenderableWidget(Button.builder(Component.literal("[+]"), btn -> {
            maxOwnerAlertDistance = Math.min(500, maxOwnerAlertDistance + 10);
            rebuildConfigWidgets();
        }).bounds(inputLeft + 200, startY + 146, 30, 20).build());
    }

    private void initTrackedItemsTab(int centerX, int startY) {
        int listLeft = centerX - 180;

        addItemBox = new EditBox(font, listLeft, startY, 260, 18, Component.literal("Item ID"));
        addItemBox.setMaxLength(64);
        addItemBox.setHint(Component.literal("§8minecraft:netherite_ingot"));
        addRenderableWidget(addItemBox);

        addRenderableWidget(Button.builder(Component.literal("§a+ Add"), btn -> {
            String item = addItemBox.getValue().trim();
            if (!item.isEmpty() && !trackedItems.contains(item)) {
                trackedItems.add(item);
                addItemBox.setValue("");
                rebuildConfigWidgets();
            }
        }).bounds(listLeft + 265, startY, 70, 18).build());

        // Render tracked item chips (up to 8 visible with remove button)
        int itemY = startY + 26;
        for (int i = 0; i < Math.min(8, trackedItems.size()); i++) {
            final String item = trackedItems.get(i);
            int curY = itemY + (i * 20);

            addRenderableWidget(Button.builder(Component.literal(item), btn -> {})
                    .bounds(listLeft, curY, 280, 18).build());

            addRenderableWidget(Button.builder(Component.literal("§c✖"), btn -> {
                trackedItems.remove(item);
                rebuildConfigWidgets();
            }).bounds(listLeft + 285, curY, 50, 18).build());
        }
    }

    private void initGeneralTab(int centerX, int startY) {
        int inputLeft = centerX - 115;

        addRenderableWidget(Button.builder(
                Component.literal("§aAuto-Claim on Place: ENABLED"),
                btn -> {})
                .bounds(inputLeft, startY, 230, 20)
                .tooltip(Tooltip.create(Component.literal("Automatically claims containers for placer")))
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("Wand Tool: minecraft:stick"),
                btn -> {})
                .bounds(inputLeft, startY + 26, 230, 20).build());
    }

    private void initWebTab(int centerX, int startY) {
        int labelLeft = centerX - 180;
        int inputLeft = centerX - 50;

        addRenderableWidget(Button.builder(
                Component.literal(webEnabled ? "§aWeb Server: ENABLED" : "§cWeb Server: DISABLED"),
                btn -> { webEnabled = !webEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY, 230, 20).build());

        webHostBox = new EditBox(font, inputLeft, startY + 26, 230, 18, Component.literal("Host"));
        webHostBox.setMaxLength(64);
        webHostBox.setValue(webHost);
        webHostBox.setHint(Component.literal("§8127.0.0.1"));
        addRenderableWidget(webHostBox);

        webPortBox = new EditBox(font, inputLeft, startY + 50, 230, 18, Component.literal("Port"));
        webPortBox.setMaxLength(6);
        webPortBox.setValue(String.valueOf(webPort));
        webPortBox.setHint(Component.literal("§88080"));
        addRenderableWidget(webPortBox);

        addRenderableWidget(Button.builder(
                Component.literal("Secret Token: " + (secretToken.length() > 10 ? secretToken.substring(0, 8) + "..." : secretToken)),
                btn -> {})
                .bounds(inputLeft, startY + 74, 230, 20)
                .tooltip(Tooltip.create(Component.literal("Full Token: " + secretToken)))
                .build());
    }

    private void saveAndApply() {
        syncInputsToState();

        ChestLogConfigUpdatePayload updatePayload = new ChestLogConfigUpdatePayload(
                alertEnabled,
                discordWebhookUrl,
                botUsername,
                avatarUrl,
                alertCooldownSeconds,
                actionBarNoticeEnabled,
                inGameChatAlertEnabled,
                maxOwnerAlertDistance,
                trackedItems,
                webEnabled,
                webHost,
                webPort
        );

        ClientPlayNetworking.send(updatePayload);
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
