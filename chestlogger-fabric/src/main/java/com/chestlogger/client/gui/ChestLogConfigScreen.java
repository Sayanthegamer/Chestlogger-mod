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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 100% custom, zero-dependency in-game configuration screen for ChestLogger (Minecraft 26.2).
 */
public class ChestLogConfigScreen extends Screen {

    public enum Tab {
        ALERTS("🚨 Alerts"),
        TRACKED_ITEMS("💎 Items"),
        GENERAL("⚙️ General"),
        WEB("🌐 Web");

        private final String label;
        Tab(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    protected final Screen parent;
    private final ChestLogConfigPayload initialPayload;
    private Tab currentTab = Tab.ALERTS;

    // Layout Bounds
    private int guiLeft;
    private int guiTop;
    private int guiWidth;
    private int guiHeight;

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

    public ChestLogConfigScreen(Screen parent, ChestLogConfigPayload payload) {
        super(Component.literal("ChestLogger Configuration"));
        this.parent = parent;
        this.initialPayload = payload != null ? payload : ChestLogConfigPayload.createDefault();

        this.alertEnabled = this.initialPayload.alertEnabled();
        this.discordWebhookUrl = this.initialPayload.discordWebhookUrl();
        this.botUsername = this.initialPayload.botUsername();
        this.avatarUrl = this.initialPayload.avatarUrl();
        this.alertCooldownSeconds = this.initialPayload.alertCooldownSeconds();
        this.actionBarNoticeEnabled = this.initialPayload.actionBarNoticeEnabled();
        this.inGameChatAlertEnabled = this.initialPayload.inGameChatAlertEnabled();
        this.maxOwnerAlertDistance = this.initialPayload.maxOwnerAlertDistance();
        this.trackedItems.addAll(this.initialPayload.trackedItems());
        this.webEnabled = this.initialPayload.webEnabled();
        this.webHost = this.initialPayload.webHost();
        this.webPort = this.initialPayload.webPort();
        this.secretToken = this.initialPayload.secretToken();
    }

    public ChestLogConfigScreen(Screen parent) {
        this(parent, ChestLogConfigPayload.createDefault());
    }

    public ChestLogConfigScreen(ChestLogConfigPayload payload) {
        this(null, payload);
    }

    @Override
    protected void init() {
        super.init();

        this.guiWidth = Math.min(480, this.width - 20);
        this.guiHeight = Math.min(270, this.height - 20);
        this.guiLeft = (this.width - this.guiWidth) / 2;
        this.guiTop = (this.height - this.guiHeight) / 2;

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

        // 1. Tab Bar Navigation
        int tabWidth = (this.guiWidth - 30) / 4;
        int tabY = this.guiTop + 6;
        int tabStartX = this.guiLeft + (this.guiWidth - (tabWidth * 4 + 6)) / 2;

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            boolean selected = (currentTab == tab);
            int x = tabStartX + (i * (tabWidth + 2));

            addRenderableWidget(Button.builder(
                    Component.literal(selected ? "§e§l" + tab.getLabel() : "§7" + tab.getLabel()),
                    btn -> { currentTab = tab; rebuildConfigWidgets(); })
                    .bounds(x, tabY, tabWidth, 20).build());
        }

        // 2. Tab Content Controls
        int contentY = this.guiTop + 38;
        int inputLeft = this.guiLeft + 135;
        int inputWidth = this.guiWidth - 150;

        switch (currentTab) {
            case ALERTS -> initAlertsTab(inputLeft, inputWidth, contentY);
            case TRACKED_ITEMS -> initTrackedItemsTab(contentY);
            case GENERAL -> initGeneralTab(inputLeft, inputWidth, contentY);
            case WEB -> initWebTab(inputLeft, inputWidth, contentY);
        }

        // 3. Footer Action Buttons
        int footerY = this.guiTop + this.guiHeight - 25;
        int btnWidth = 100;
        int centerX = this.guiLeft + this.guiWidth / 2;

        addRenderableWidget(Button.builder(
                Component.literal("§a💾 Save & Apply"),
                btn -> saveAndApply())
                .bounds(centerX - btnWidth - 6, footerY, btnWidth, 18)
                .tooltip(Tooltip.create(Component.literal("Save changes and hot-reload live on server")))
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("§cCancel"),
                btn -> onClose())
                .bounds(centerX + 6, footerY, btnWidth, 18)
                .build());
    }

    private void initAlertsTab(int inputLeft, int inputWidth, int startY) {
        // 1. Discord Alerts Enabled Toggle
        addRenderableWidget(Button.builder(
                Component.literal(alertEnabled ? "§aDiscord Alerts: ENABLED" : "§cDiscord Alerts: DISABLED"),
                btn -> { alertEnabled = !alertEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY, inputWidth, 20).build());

        // 2. Webhook URL Box
        webhookUrlBox = new EditBox(font, inputLeft, startY + 24, inputWidth, 18, Component.literal("Discord Webhook URL"));
        webhookUrlBox.setMaxLength(512);
        webhookUrlBox.setValue(discordWebhookUrl);
        webhookUrlBox.setHint(Component.literal("§8https://discord.com/api/webhooks/..."));
        addRenderableWidget(webhookUrlBox);

        // 3. Bot Username Box
        botUsernameBox = new EditBox(font, inputLeft, startY + 46, inputWidth, 18, Component.literal("Bot Username"));
        botUsernameBox.setMaxLength(64);
        botUsernameBox.setValue(botUsername);
        botUsernameBox.setHint(Component.literal("§8ChestLogger Alerts"));
        addRenderableWidget(botUsernameBox);

        // 4. Cooldown Stepper
        int stepperWidth = inputWidth - 68;
        addRenderableWidget(Button.builder(Component.literal("[-]"), btn -> {
            alertCooldownSeconds = Math.max(5, alertCooldownSeconds - 5);
            rebuildConfigWidgets();
        }).bounds(inputLeft, startY + 68, 30, 20).build());

        addRenderableWidget(Button.builder(Component.literal(alertCooldownSeconds + "s Cooldown"), btn -> {})
                .bounds(inputLeft + 34, startY + 68, stepperWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("[+]"), btn -> {
            alertCooldownSeconds = Math.min(300, alertCooldownSeconds + 5);
            rebuildConfigWidgets();
        }).bounds(inputLeft + inputWidth - 30, startY + 68, 30, 20).build());

        // 5. In-Game HUD Notice Toggle
        addRenderableWidget(Button.builder(
                Component.literal(actionBarNoticeEnabled ? "§aAction-Bar HUD: ENABLED" : "§7Action-Bar HUD: DISABLED"),
                btn -> { actionBarNoticeEnabled = !actionBarNoticeEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY + 92, inputWidth, 20).build());

        // 6. In-Game Chat Card Toggle
        addRenderableWidget(Button.builder(
                Component.literal(inGameChatAlertEnabled ? "§aChat Alert Cards: ENABLED" : "§7Chat Alert Cards: DISABLED"),
                btn -> { inGameChatAlertEnabled = !inGameChatAlertEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY + 116, inputWidth, 20).build());

        // 7. Max Owner Distance Stepper
        addRenderableWidget(Button.builder(Component.literal("[-]"), btn -> {
            maxOwnerAlertDistance = Math.max(10, maxOwnerAlertDistance - 10);
            rebuildConfigWidgets();
        }).bounds(inputLeft, startY + 140, 30, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Owner Distance: " + maxOwnerAlertDistance + "m"), btn -> {})
                .bounds(inputLeft + 34, startY + 140, stepperWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("[+]"), btn -> {
            maxOwnerAlertDistance = Math.min(500, maxOwnerAlertDistance + 10);
            rebuildConfigWidgets();
        }).bounds(inputLeft + inputWidth - 30, startY + 140, 30, 20).build());
    }

    private void initTrackedItemsTab(int startY) {
        int listLeft = this.guiLeft + 16;
        int listWidth = this.guiWidth - 32;

        addItemBox = new EditBox(font, listLeft, startY + 12, listWidth - 75, 18, Component.literal("Item ID"));
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
        }).bounds(listLeft + listWidth - 70, startY + 12, 70, 18).build());

        // Render tracked item chips
        int itemY = startY + 44;
        for (int i = 0; i < Math.min(6, trackedItems.size()); i++) {
            final String item = trackedItems.get(i);
            int curY = itemY + (i * 20);

            addRenderableWidget(Button.builder(Component.literal(item), btn -> {})
                    .bounds(listLeft, curY, listWidth - 50, 18).build());

            addRenderableWidget(Button.builder(Component.literal("§c✖"), btn -> {
                trackedItems.remove(item);
                rebuildConfigWidgets();
            }).bounds(listLeft + listWidth - 46, curY, 46, 18).build());
        }
    }

    private void initGeneralTab(int inputLeft, int inputWidth, int startY) {
        addRenderableWidget(Button.builder(
                Component.literal("§aAuto-Claim on Place: ENABLED"),
                btn -> {})
                .bounds(inputLeft, startY, inputWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Automatically claims containers for placer")))
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("Wand Tool: minecraft:stick"),
                btn -> {})
                .bounds(inputLeft, startY + 26, inputWidth, 20).build());
    }

    private void initWebTab(int inputLeft, int inputWidth, int startY) {
        addRenderableWidget(Button.builder(
                Component.literal(webEnabled ? "§aWeb Server: ENABLED" : "§cWeb Server: DISABLED"),
                btn -> { webEnabled = !webEnabled; rebuildConfigWidgets(); })
                .bounds(inputLeft, startY, inputWidth, 20).build());

        webHostBox = new EditBox(font, inputLeft, startY + 24, inputWidth, 18, Component.literal("Host"));
        webHostBox.setMaxLength(64);
        webHostBox.setValue(webHost);
        webHostBox.setHint(Component.literal("§8127.0.0.1"));
        addRenderableWidget(webHostBox);

        webPortBox = new EditBox(font, inputLeft, startY + 46, inputWidth, 18, Component.literal("Port"));
        webPortBox.setMaxLength(6);
        webPortBox.setValue(String.valueOf(webPort));
        webPortBox.setHint(Component.literal("§88080"));
        addRenderableWidget(webPortBox);

        addRenderableWidget(Button.builder(
                Component.literal("Token: " + (secretToken != null && secretToken.length() > 10 ? secretToken.substring(0, 8) + "..." : secretToken)),
                btn -> {})
                .bounds(inputLeft, startY + 68, inputWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Full Token: " + secretToken)))
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 1. Background Panel
        renderPanelBackground(graphics);

        // 2. Tab Section Field Labels
        renderFieldLabels(graphics);

        // 3. Child widgets (buttons, textboxes)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
    }

    private void renderPanelBackground(GuiGraphicsExtractor graphics) {
        int x1 = this.guiLeft;
        int y1 = this.guiTop;
        int x2 = this.guiLeft + this.guiWidth;
        int y2 = this.guiTop + this.guiHeight;

        // Outer black border
        graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF000000);
        // Dark panel body
        graphics.fill(x1, y1, x2, y2, 0xEE16161C);
        // Header background
        graphics.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 30, 0xFF202028);

        // Separators
        graphics.fill(x1 + 4, y1 + 30, x2 - 4, y1 + 31, 0xFF383844);
        graphics.fill(x1 + 4, y2 - 28, x2 - 4, y2 - 27, 0xFF2A2A34);
    }

    private void renderFieldLabels(GuiGraphicsExtractor graphics) {
        Font font = this.font;
        int labelLeft = this.guiLeft + 14;
        int startY = this.guiTop + 38;

        switch (currentTab) {
            case ALERTS -> {
                graphics.text(font, "§7Discord Alert:", labelLeft, startY + 6, 0xFFB0B0B0);
                graphics.text(font, "§7Webhook URL:", labelLeft, startY + 29, 0xFFB0B0B0);
                graphics.text(font, "§7Bot Username:", labelLeft, startY + 51, 0xFFB0B0B0);
                graphics.text(font, "§7Alert Cooldown:", labelLeft, startY + 74, 0xFFB0B0B0);
                graphics.text(font, "§7Action-Bar HUD:", labelLeft, startY + 98, 0xFFB0B0B0);
                graphics.text(font, "§7Chat Cards:", labelLeft, startY + 122, 0xFFB0B0B0);
                graphics.text(font, "§7Owner Radius:", labelLeft, startY + 146, 0xFFB0B0B0);
            }
            case TRACKED_ITEMS -> {
                graphics.text(font, "§7Enter Item ID to track:", labelLeft, startY + 1, 0xFFB0B0B0);
                graphics.text(font, "§6Tracked Valuables (Immediate Theft/Raid Alarms):", labelLeft, startY + 34, 0xFFFFCC55);
            }
            case GENERAL -> {
                graphics.text(font, "§7Auto-Claim:", labelLeft, startY + 6, 0xFFB0B0B0);
                graphics.text(font, "§7Admin Wand:", labelLeft, startY + 32, 0xFFB0B0B0);
            }
            case WEB -> {
                graphics.text(font, "§7Web Server:", labelLeft, startY + 6, 0xFFB0B0B0);
                graphics.text(font, "§7Host Address:", labelLeft, startY + 29, 0xFFB0B0B0);
                graphics.text(font, "§7HTTP Port:", labelLeft, startY + 51, 0xFFB0B0B0);
                graphics.text(font, "§7Secret Token:", labelLeft, startY + 74, 0xFFB0B0B0);
            }
        }
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

        try {
            if (ClientPlayNetworking.canSend(ChestLogConfigUpdatePayload.TYPE)) {
                ClientPlayNetworking.send(updatePayload);
            }
        } catch (Throwable ignored) {
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.parent != null) {
            this.minecraft.gui.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
