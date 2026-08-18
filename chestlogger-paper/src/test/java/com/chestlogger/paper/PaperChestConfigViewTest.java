package com.chestlogger.paper;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.config.ConfigManager;
import com.chestlogger.web.WebConfig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("PaperChestConfigView Unit Tests")
class PaperChestConfigViewTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private Player mockPlayer;
    private List<String> playerMessages;
    private AtomicBoolean inventoryClosed;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(tempDir);
        playerMessages = new ArrayList<>();
        inventoryClosed = new AtomicBoolean(false);
        mockPlayer = createMockPlayer("AdminUser", UUID.randomUUID(), playerMessages, inventoryClosed);
    }

    @Nested
    @DisplayName("1. Instantiation & Default Tab")
    class InstantiationTests {

        @Test
        @DisplayName("Instantiates with Player, ConfigManager, and default Category (Tab.ALERTS)")
        void testDefaultInstantiation() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            assertThat(view.getPlayer()).isSameAs(mockPlayer);
            assertThat(view.getConfigManager()).isSameAs(configManager);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.ALERTS);
            assertThat(view.isAlertEnabled()).isEqualTo(configManager.getAlertConfig().enabled());
            assertThat(view.isActionBarNoticeEnabled()).isEqualTo(configManager.isActionBarNoticeEnabled());
            assertThat(view.isInGameChatAlertEnabled()).isEqualTo(configManager.isInGameChatAlertEnabled());
            assertThat(view.isWebEnabled()).isEqualTo(configManager.getWebConfig().isEnabled());
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(configManager.getAlertConfig().rateLimitPerMinute());
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(configManager.getMaxOwnerAlertDistance());
        }

        @Test
        @DisplayName("Instantiates with explicit initial Tab")
        void testExplicitTabInstantiation() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager, PaperChestConfigView.Tab.WEB);

            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.WEB);
        }
    }

    @Nested
    @DisplayName("2. Category Tab Rendering & Switching")
    class TabNavigationTests {

        @Test
        @DisplayName("Tab enum contains all four categories: ALERTS, TRACKED_ITEMS, GENERAL, WEB")
        void testTabEnumCategories() {
            assertThat(PaperChestConfigView.Tab.values()).containsExactly(
                    PaperChestConfigView.Tab.ALERTS,
                    PaperChestConfigView.Tab.TRACKED_ITEMS,
                    PaperChestConfigView.Tab.GENERAL,
                    PaperChestConfigView.Tab.WEB
            );
        }

        @Test
        @DisplayName("Switch tabs programmatically across all categories")
        void testProgrammaticTabSwitching() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            view.setTab(PaperChestConfigView.Tab.TRACKED_ITEMS);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.TRACKED_ITEMS);

            view.setTab(PaperChestConfigView.Tab.GENERAL);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.GENERAL);

            view.setTab(PaperChestConfigView.Tab.WEB);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.WEB);

            view.setTab(PaperChestConfigView.Tab.ALERTS);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.ALERTS);
        }

        @Test
        @DisplayName("Switch tabs by clicking tab header navigation slots")
        void testSlotClickTabSwitching() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            // Click TRACKED_ITEMS tab slot
            view.handleSlotClick(PaperChestConfigView.SLOT_TAB_TRACKED_ITEMS);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.TRACKED_ITEMS);

            // Click GENERAL tab slot
            view.handleSlotClick(PaperChestConfigView.SLOT_TAB_GENERAL);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.GENERAL);

            // Click WEB tab slot
            view.handleSlotClick(PaperChestConfigView.SLOT_TAB_WEB);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.WEB);

            // Click ALERTS tab slot
            view.handleSlotClick(PaperChestConfigView.SLOT_TAB_ALERTS);
            assertThat(view.getCurrentTab()).isEqualTo(PaperChestConfigView.Tab.ALERTS);
        }
    }

    @Nested
    @DisplayName("3. Toggle Actions Mutating Settings")
    class ToggleActionTests {

        @Test
        @DisplayName("Toggle Discord alert enabled state")
        void testToggleAlertEnabled() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            boolean initial = view.isAlertEnabled();

            view.toggleAlerts();
            assertThat(view.isAlertEnabled()).isEqualTo(!initial);

            view.toggleAlerts();
            assertThat(view.isAlertEnabled()).isEqualTo(initial);
        }

        @Test
        @DisplayName("Toggle Action-Bar HUD notice setting")
        void testToggleActionBarNotice() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            boolean initial = view.isActionBarNoticeEnabled();

            view.toggleActionBarNotice();
            assertThat(view.isActionBarNoticeEnabled()).isEqualTo(!initial);

            view.toggleActionBarNotice();
            assertThat(view.isActionBarNoticeEnabled()).isEqualTo(initial);
        }

        @Test
        @DisplayName("Toggle In-Game Chat alert cards setting")
        void testToggleInGameChatAlerts() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            boolean initial = view.isInGameChatAlertEnabled();

            view.toggleChatAlerts();
            assertThat(view.isInGameChatAlertEnabled()).isEqualTo(!initial);

            view.toggleChatAlerts();
            assertThat(view.isInGameChatAlertEnabled()).isEqualTo(initial);
        }

        @Test
        @DisplayName("Toggle Web server enabled state")
        void testToggleWebEnabled() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            boolean initial = view.isWebEnabled();

            view.toggleWebEnabled();
            assertThat(view.isWebEnabled()).isEqualTo(!initial);

            view.toggleWebEnabled();
            assertThat(view.isWebEnabled()).isEqualTo(initial);
        }

        @Test
        @DisplayName("Clicking toggle slots mutates corresponding toggle settings")
        void testSlotClicksForToggles() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            // Alerts Tab Toggles
            view.setTab(PaperChestConfigView.Tab.ALERTS);
            boolean initAlert = view.isAlertEnabled();
            view.handleSlotClick(PaperChestConfigView.SLOT_TOGGLE_ALERTS);
            assertThat(view.isAlertEnabled()).isEqualTo(!initAlert);

            boolean initActionBar = view.isActionBarNoticeEnabled();
            view.handleSlotClick(PaperChestConfigView.SLOT_TOGGLE_ACTION_BAR);
            assertThat(view.isActionBarNoticeEnabled()).isEqualTo(!initActionBar);

            boolean initChat = view.isInGameChatAlertEnabled();
            view.handleSlotClick(PaperChestConfigView.SLOT_TOGGLE_CHAT_ALERTS);
            assertThat(view.isInGameChatAlertEnabled()).isEqualTo(!initChat);

            // Web Tab Toggle
            view.setTab(PaperChestConfigView.Tab.WEB);
            boolean initWeb = view.isWebEnabled();
            view.handleSlotClick(PaperChestConfigView.SLOT_TOGGLE_WEB);
            assertThat(view.isWebEnabled()).isEqualTo(!initWeb);
        }
    }

    @Nested
    @DisplayName("4. Numeric Adjusters (Cooldown & Distance)")
    class NumericAdjusterTests {

        @Test
        @DisplayName("Adjust alert cooldown seconds with increment and decrement")
        void testAdjustCooldown() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            int initialCooldown = view.getAlertCooldownSeconds();

            view.adjustCooldown(5);
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(initialCooldown + 5);

            view.adjustCooldown(-10);
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(initialCooldown - 5);
        }

        @Test
        @DisplayName("Cooldown value clamps at lower bound (5s) and upper bound (300s)")
        void testCooldownClamping() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            // Clamping below 5s
            view.adjustCooldown(-1000);
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(5);

            // Clamping above 300s
            view.adjustCooldown(1000);
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(300);
        }

        @Test
        @DisplayName("Adjust max owner alert distance with increment and decrement")
        void testAdjustDistance() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            int initialDistance = view.getMaxOwnerAlertDistance();

            view.adjustDistance(20);
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(initialDistance + 20);

            view.adjustDistance(-30);
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(initialDistance - 10);
        }

        @Test
        @DisplayName("Distance value clamps at lower bound (10m) and upper bound (500m)")
        void testDistanceClamping() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            // Clamping below 10m
            view.adjustDistance(-1000);
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(10);

            // Clamping above 500m
            view.adjustDistance(1000);
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(500);
        }

        @Test
        @DisplayName("Clicking cooldown and distance adjuster slots modifies values")
        void testAdjusterSlotClicks() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            view.setTab(PaperChestConfigView.Tab.ALERTS);

            int initCooldown = view.getAlertCooldownSeconds();
            view.handleSlotClick(PaperChestConfigView.SLOT_COOLDOWN_INCREASE);
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(initCooldown + 5);

            view.handleSlotClick(PaperChestConfigView.SLOT_COOLDOWN_DECREASE);
            assertThat(view.getAlertCooldownSeconds()).isEqualTo(initCooldown);

            int initDistance = view.getMaxOwnerAlertDistance();
            view.handleSlotClick(PaperChestConfigView.SLOT_DISTANCE_INCREASE);
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(initDistance + 10);

            view.handleSlotClick(PaperChestConfigView.SLOT_DISTANCE_DECREASE);
            assertThat(view.getMaxOwnerAlertDistance()).isEqualTo(initDistance);
        }
    }

    @Nested
    @DisplayName("5. Tracked Items List & Removal on Click")
    class TrackedItemsTests {

        @Test
        @DisplayName("Loads initial tracked items from config")
        void testInitialTrackedItems() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            assertThat(view.getTrackedItems())
                    .contains("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra");
        }

        @Test
        @DisplayName("Add and remove tracked items dynamically")
        void testAddAndRemoveTrackedItems() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            view.addTrackedItem("minecraft:dragon_egg");
            assertThat(view.getTrackedItems()).contains("minecraft:dragon_egg");

            view.removeTrackedItem("minecraft:dragon_egg");
            assertThat(view.getTrackedItems()).doesNotContain("minecraft:dragon_egg");
        }

        @Test
        @DisplayName("Clicking a tracked item slot in TRACKED_ITEMS tab removes the item")
        void testClickTrackedItemSlotRemovesItem() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            view.setTab(PaperChestConfigView.Tab.TRACKED_ITEMS);

            List<String> itemsBefore = new ArrayList<>(view.getTrackedItems());
            assertThat(itemsBefore).isNotEmpty();

            String firstItem = itemsBefore.get(0);
            int firstItemSlot = PaperChestConfigView.TRACKED_ITEMS_START_SLOT;

            view.handleSlotClick(firstItemSlot);

            assertThat(view.getTrackedItems()).doesNotContain(firstItem);
            assertThat(view.getTrackedItems()).hasSize(itemsBefore.size() - 1);
        }

        @Test
        @DisplayName("Adding invalid or duplicate tracked items is handled cleanly")
        void testAddInvalidTrackedItems() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            int initialCount = view.getTrackedItems().size();

            view.addTrackedItem(null);
            view.addTrackedItem("");
            view.addTrackedItem("   ");
            assertThat(view.getTrackedItems()).hasSize(initialCount);

            // Duplicate item
            view.addTrackedItem("minecraft:diamond");
            assertThat(view.getTrackedItems()).hasSize(initialCount);
        }
    }

    @Nested
    @DisplayName("6. Saving Modifications Atomically to ConfigManager")
    class PersistenceTests {

        @Test
        @DisplayName("Modifications are saved atomically and persisted to ConfigManager")
        void testSaveAtomicallyToConfigManager() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);

            // Mutate settings in view
            view.toggleAlerts();
            view.toggleActionBarNotice();
            view.toggleChatAlerts();
            view.toggleWebEnabled();
            view.adjustCooldown(15);
            view.adjustDistance(50);
            view.addTrackedItem("minecraft:nether_star");
            view.removeTrackedItem("minecraft:diamond");

            boolean expectedAlert = view.isAlertEnabled();
            boolean expectedActionBar = view.isActionBarNoticeEnabled();
            boolean expectedChat = view.isInGameChatAlertEnabled();
            boolean expectedWeb = view.isWebEnabled();
            int expectedCooldown = view.getAlertCooldownSeconds();
            int expectedDistance = view.getMaxOwnerAlertDistance();

            // Save
            view.save();

            // Verify live ConfigManager values
            assertThat(configManager.getAlertConfig().enabled()).isEqualTo(expectedAlert);
            assertThat(configManager.isActionBarNoticeEnabled()).isEqualTo(expectedActionBar);
            assertThat(configManager.isInGameChatAlertEnabled()).isEqualTo(expectedChat);
            assertThat(configManager.getWebConfig().isEnabled()).isEqualTo(expectedWeb);
            assertThat(configManager.getAlertConfig().rateLimitPerMinute()).isEqualTo(expectedCooldown);
            assertThat(configManager.getMaxOwnerAlertDistance()).isEqualTo(expectedDistance);
            assertThat(configManager.getAlertConfig().valuableItems()).contains("minecraft:nether_star");
            assertThat(configManager.getAlertConfig().valuableItems()).doesNotContain("minecraft:diamond");

            // Verify persistence by reloading from disk on a fresh instance
            ConfigManager reloadedManager = new ConfigManager(tempDir);
            assertThat(reloadedManager.getAlertConfig().enabled()).isEqualTo(expectedAlert);
            assertThat(reloadedManager.getWebConfig().isEnabled()).isEqualTo(expectedWeb);
            assertThat(reloadedManager.getAlertConfig().rateLimitPerMinute()).isEqualTo(expectedCooldown);
            assertThat(reloadedManager.getAlertConfig().valuableItems()).contains("minecraft:nether_star");
            assertThat(reloadedManager.getAlertConfig().valuableItems()).doesNotContain("minecraft:diamond");
        }

        @Test
        @DisplayName("Clicking save slot saves changes and closes inventory")
        void testSaveSlotClickClosesInventory() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            view.toggleAlerts();
            boolean newAlertState = view.isAlertEnabled();

            view.handleSlotClick(PaperChestConfigView.SLOT_SAVE);

            assertThat(configManager.getAlertConfig().enabled()).isEqualTo(newAlertState);
            assertThat(inventoryClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Clicking close slot closes inventory without saving pending modifications")
        void testCloseSlotClickDiscardsUnsaved() {
            PaperChestConfigView view = new PaperChestConfigView(mockPlayer, configManager);
            boolean origAlertState = configManager.getAlertConfig().enabled();

            view.toggleAlerts();
            assertThat(view.isAlertEnabled()).isNotEqualTo(origAlertState);

            view.handleSlotClick(PaperChestConfigView.SLOT_CLOSE);

            assertThat(configManager.getAlertConfig().enabled()).isEqualTo(origAlertState);
            assertThat(inventoryClosed.get()).isTrue();
        }
    }

    private static Player createMockPlayer(
            String name,
            UUID uuid,
            List<String> messages,
            AtomicBoolean closed
    ) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getName".equals(m)) return name;
                    if ("getUniqueId".equals(m)) return uuid;
                    if ("hasPermission".equals(m)) return true;
                    if ("isOp".equals(m)) return true;
                    if ("sendMessage".equals(m)) {
                        if (args != null && args.length > 0 && args[0] != null) {
                            messages.add(args[0].toString());
                        }
                        return null;
                    }
                    if ("closeInventory".equals(m)) {
                        closed.set(true);
                        return null;
                    }
                    if ("openInventory".equals(m)) return null;
                    if ("playSound".equals(m)) return null;
                    if ("equals".equals(m)) return proxy == args[0];
                    if ("hashCode".equals(m)) return uuid.hashCode();
                    if ("toString".equals(m)) return "TestPlayer[" + name + "]";
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) return false;
                    if (r == int.class) return 0;
                    return null;
                }
        );
    }
}
