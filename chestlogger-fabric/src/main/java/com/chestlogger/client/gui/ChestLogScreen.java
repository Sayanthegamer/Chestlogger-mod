package com.chestlogger.client.gui;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.network.ChestLogPagePayload;
import com.chestlogger.network.ChestLogPageRequestPayload;
import com.chestlogger.network.DisplayRecord;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Pure client-side audit history viewer screen for ChestLogger (Minecraft 26.2).
 *
 * Design constraints:
 * 1. Extends Screen with ZERO ContainerMenu, Slot, or fake inventory dependencies.
 * 2. Header displays container block type, coordinates (X, Y, Z), dimension, and total records.
 * 3. Log rows render formatted timestamps, actor type badges, action descriptions, target slots,
 *    real item icons with tooltips, and color-coded signed deltas (+Green / -Red).
 * 4. Responsive bounds adapting smoothly to varying GUI scales and window resolutions.
 * 5. Full client-server pagination and filter synchronization via Fabric networking payloads.
 */
public class ChestLogScreen extends Screen {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int ROW_HEIGHT = 20;

    // Query & Container Context State
    private UUID queryId;
    private int currentPage = 1;
    private int totalPages = 1;
    private int totalRecords = 0;
    private String containerType = "Container";
    private String dimension = "minecraft:overworld";
    private long packedBlockPos = 0L;
    private List<DisplayRecord> records = Collections.emptyList();

    // Active Filters
    private String activePlayerFilter = "";
    private String activeItemFilter = "";

    // Layout Bounds
    private int guiLeft;
    private int guiTop;
    private int guiWidth;
    private int guiHeight;

    // Child Widgets
    private ChestLogFilterWidget filterWidget;
    private ChestLogPaginationWidget paginationWidget;

    // Scrolling State
    private double scrollAmount = 0.0;
    private boolean isScrolling = false;

    // Hovered Tooltip Context
    private Component hoveredTooltip = null;
    private ItemStack hoveredItemTooltip = null;
    private int tooltipMouseX = 0;
    private int tooltipMouseY = 0;

    public ChestLogScreen(ChestLogPagePayload initialPayload) {
        super(Component.literal("ChestLogger History"));
        applyPayload(initialPayload);
    }

    public ChestLogScreen(UUID queryId, String containerType, String dimension, long packedBlockPos) {
        super(Component.literal("ChestLogger History"));
        this.queryId = queryId != null ? queryId : UUID.randomUUID();
        this.containerType = containerType != null ? containerType : "Container";
        this.dimension = dimension != null ? dimension : "minecraft:overworld";
        this.packedBlockPos = packedBlockPos;
    }

    private void applyPayload(ChestLogPagePayload payload) {
        if (payload == null) {
            return;
        }
        this.queryId = payload.queryId();
        this.currentPage = payload.pageIndex();
        this.totalPages = Math.max(1, payload.totalPages());
        this.totalRecords = payload.totalRecords();
        this.containerType = payload.containerType();
        this.dimension = payload.dimension();
        this.packedBlockPos = payload.packedBlockPos();
        this.records = new ArrayList<>(payload.records());
        this.scrollAmount = 0.0;
    }

    public void updatePage(ChestLogPagePayload payload) {
        applyPayload(payload);
        if (paginationWidget != null) {
            paginationWidget.setPageInfo(currentPage, totalPages);
        }
    }

    public UUID getQueryId() {
        return queryId;
    }

    @Override
    protected void init() {
        super.init();

        // Compute responsive modal dimensions
        this.guiWidth = Math.clamp(this.width - 24, 420, 560);
        this.guiHeight = Math.clamp(this.height - 24, 230, 360);
        this.guiLeft = (this.width - this.guiWidth) / 2;
        this.guiTop = (this.height - this.guiHeight) / 2;

        Font font = this.font;

        // Initialize Filter Bar Widget (Top section)
        int filterY = this.guiTop + 38;
        int filterHeight = 20;
        this.filterWidget = new ChestLogFilterWidget(
                this.guiLeft + 6, filterY, this.guiWidth - 12, filterHeight,
                font,
                (playerFilter, itemFilter) -> {
                    this.activePlayerFilter = playerFilter;
                    this.activeItemFilter = itemFilter;
                    requestPage(1, playerFilter, itemFilter);
                },
                () -> {
                    this.activePlayerFilter = "";
                    this.activeItemFilter = "";
                    requestPage(1, "", "");
                }
        );
        this.filterWidget.setFilters(activePlayerFilter, activeItemFilter);
        addRenderableWidget(this.filterWidget);

        // Initialize Pagination Bar Widget (Bottom section)
        int paginationHeight = 22;
        int paginationY = this.guiTop + this.guiHeight - paginationHeight - 5;
        this.paginationWidget = new ChestLogPaginationWidget(
                this.guiLeft + 6, paginationY, this.guiWidth - 12, paginationHeight,
                page -> requestPage(page, activePlayerFilter, activeItemFilter),
                () -> requestPage(currentPage, activePlayerFilter, activeItemFilter),
                this::onClose
        );
        this.paginationWidget.setPageInfo(currentPage, totalPages);
        addRenderableWidget(this.paginationWidget);
    }

    private void requestPage(int targetPage, String playerFilter, String itemFilter) {
        if (Minecraft.getInstance().getConnection() != null && queryId != null) {
            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId,
                    targetPage,
                    packedBlockPos,
                    dimension,
                    (playerFilter != null && !playerFilter.isBlank()) ? playerFilter.trim() : null,
                    (itemFilter != null && !itemFilter.isBlank()) ? itemFilter.trim() : null
            );
            ClientPlayNetworking.send(request);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredTooltip = null;
        this.hoveredItemTooltip = null;

        // 1. Dark modal backdrop overlay
        this.extractBackground(graphics, mouseX, mouseY, partialTick);

        // 2. Main GUI Frame and Borders
        renderPanelBackground(graphics);

        // 3. Header Section (Container metadata & coords)
        renderHeader(graphics);

        // 4. Table Column Headers
        int tableTop = this.guiTop + 62;
        renderTableHeaders(graphics, tableTop);

        // 5. Scrollable Log Rows Viewport
        int rowsTop = tableTop + 14;
        int rowsBottom = this.guiTop + this.guiHeight - 32;
        int viewportHeight = rowsBottom - rowsTop;

        renderLogRows(graphics, mouseX, mouseY, rowsTop, rowsBottom, viewportHeight);

        // 6. Child widgets (Filter bar & Pagination controls)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // 7. Render hovered tooltip last
        if (hoveredItemTooltip != null && !hoveredItemTooltip.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, hoveredItemTooltip, tooltipMouseX, tooltipMouseY);
        } else if (hoveredTooltip != null) {
            graphics.setTooltipForNextFrame(hoveredTooltip, tooltipMouseX, tooltipMouseY);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Semi-transparent dark backdrop
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
    }

    private void renderPanelBackground(GuiGraphicsExtractor graphics) {
        int x1 = this.guiLeft;
        int y1 = this.guiTop;
        int x2 = this.guiLeft + this.guiWidth;
        int y2 = this.guiTop + this.guiHeight;

        // Outer border
        graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF000000);
        // Main panel body
        graphics.fill(x1, y1, x2, y2, 0xEE16161C);

        // Header section background
        graphics.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 36, 0xFF202028);

        // Separators
        graphics.fill(x1 + 4, y1 + 36, x2 - 4, y1 + 37, 0xFF383844);
        graphics.fill(x1 + 4, y1 + 60, x2 - 4, y1 + 61, 0xFF2A2A34);
        graphics.fill(x1 + 4, y2 - 30, x2 - 4, y2 - 29, 0xFF2A2A34);
    }

    private void renderHeader(GuiGraphicsExtractor graphics) {
        Font font = this.font;
        int x = this.guiLeft + 8;
        int y = this.guiTop + 6;

        String title = "§6§lChestLogger §fHistory Viewer";
        graphics.text(font, title, x, y, 0xFFFFFFFF);

        String typeBadge = String.format("§e[%s]", containerType);
        int titleWidth = font.width(title);
        graphics.text(font, typeBadge, x + titleWidth + 6, y, 0xFFE0E0E0);

        int xCoord = BlockPosUtil.unpackX(packedBlockPos);
        int yCoord = BlockPosUtil.unpackY(packedBlockPos);
        int zCoord = BlockPosUtil.unpackZ(packedBlockPos);

        String posText = String.format("§7Pos: §f(%d, %d, %d)", xCoord, yCoord, zCoord);
        String dimClean = cleanDimensionName(dimension);
        String dimText = String.format("§7Dim: §f%s", dimClean);
        String totalText = String.format("§7Total Events: §a%d", totalRecords);

        int line2Y = y + 14;
        graphics.text(font, posText, x, line2Y, 0xFFAAAAAA);
        int posWidth = font.width(posText);

        graphics.text(font, dimText, x + posWidth + 10, line2Y, 0xFFAAAAAA);

        int totalWidth = font.width(totalText);
        graphics.text(font, totalText, this.guiLeft + this.guiWidth - totalWidth - 10, line2Y, 0xFFAAAAAA);
    }

    private void renderTableHeaders(GuiGraphicsExtractor graphics, int y) {
        Font font = this.font;
        int x1 = this.guiLeft + 6;
        int x2 = this.guiLeft + this.guiWidth - 6;

        graphics.fill(x1, y, x2, y + 13, 0xFF202028);

        int textY = y + 3;
        graphics.text(font, "§7Time", getColumnX(0), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Actor", getColumnX(1), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Action", getColumnX(2), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Slot", getColumnX(3), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Item", getColumnX(4), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Delta", getColumnX(5), textY, 0xFFB0B0B0);
    }

    private int getColumnX(int columnIndex) {
        int left = this.guiLeft + 8;
        int w = this.guiWidth - 24;

        return switch (columnIndex) {
            case 0 -> left;                        // Time (~50px)
            case 1 -> left + 54;                   // Actor (~90px)
            case 2 -> left + 146;                  // Action (~80px)
            case 3 -> left + 228;                  // Slot (~36px)
            case 4 -> left + 266;                  // Item (~150px)
            case 5 -> left + w - 38;               // Delta (~38px)
            default -> left;
        };
    }

    private void renderLogRows(
            GuiGraphicsExtractor graphics,
            int mouseX, int mouseY,
            int rowsTop, int rowsBottom,
            int viewportHeight
    ) {
        Font font = this.font;
        int contentHeight = records.size() * ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollAmount = Math.clamp(scrollAmount, 0, maxScroll);

        int clipX1 = this.guiLeft + 6;
        int clipX2 = this.guiLeft + this.guiWidth - 6;

        if (records.isEmpty()) {
            String emptyMessage = (totalRecords == 0)
                    ? "§7No transaction records logged for this container."
                    : "§eNo records matching current filters.";
            graphics.centeredText(font, emptyMessage, this.guiLeft + this.guiWidth / 2, rowsTop + viewportHeight / 2 - 4, 0xFFAAAAAA);
            return;
        }

        int startIndex = (int) (scrollAmount / ROW_HEIGHT);
        int endIndex = Math.min(records.size(), startIndex + (viewportHeight / ROW_HEIGHT) + 2);

        for (int i = startIndex; i < endIndex; i++) {
            DisplayRecord record = records.get(i);
            int rowY = (int) (rowsTop + (i * ROW_HEIGHT) - scrollAmount);

            if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) {
                continue;
            }

            boolean isHovered = mouseX >= clipX1 && mouseX <= clipX2 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            // Row background (alternating or hover)
            int rowBg = isHovered ? 0xFF282834 : ((i % 2 == 0) ? 0xFF191920 : 0xFF14141A);
            graphics.fill(clipX1, rowY, clipX2, rowY + ROW_HEIGHT, rowBg);

            int textY = rowY + 6;

            // 1. Time Column
            String timeStr = TIME_FORMATTER.format(Instant.ofEpochMilli(record.timestampMs()));
            graphics.text(font, "§8" + timeStr, getColumnX(0), textY, 0xFF888888);

            // 2. Actor Column
            String actorName = truncate(record.actorName(), 11);
            String actorColor = record.actorType() == ActorType.PLAYER.getWireId() ? "§f" : "§9";
            graphics.text(font, actorColor + actorName, getColumnX(1), textY, 0xFFFFFFFF);

            // 3. Action Column
            String actionName = formatActionName(record.actionType());
            graphics.text(font, "§7" + actionName, getColumnX(2), textY, 0xFFAAAAAA);

            // 4. Slot Column
            String slotStr = record.slotIndex() >= 27
                    ? String.format("§8#%02d§7R", record.slotIndex())
                    : String.format("§8#%02d§8L", record.slotIndex());
            graphics.text(font, slotStr, getColumnX(3), textY, 0xFF777777);

            // 5. Item Column (Icon + Name)
            int itemColX = getColumnX(4);
            ItemStack stack = ItemResolver.resolve(record.itemId());
            if (!stack.isEmpty()) {
                int iconY = rowY + 2;
                graphics.item(stack, itemColX, iconY);
                if (isHovered && mouseX >= itemColX && mouseX <= itemColX + 16 && mouseY >= iconY && mouseY <= iconY + 16) {
                    this.hoveredItemTooltip = stack;
                    this.tooltipMouseX = mouseX;
                    this.tooltipMouseY = mouseY;
                }
            }

            String itemDisplayName = truncate(ItemResolver.getDisplayName(record.itemId()).getString(), 14);
            graphics.text(font, "§e" + itemDisplayName, itemColX + 18, textY, 0xFFFFFF55);

            // 6. Delta Column
            int delta = record.quantityDelta();
            String deltaStr;
            int deltaColor;
            if (delta > 0) {
                deltaStr = "+" + delta;
                deltaColor = 0xFF55FF55; // Bright Green
            } else if (delta < 0) {
                deltaStr = String.valueOf(delta);
                deltaColor = 0xFFFF5555; // Bright Red
            } else {
                deltaStr = "0";
                deltaColor = 0xFFAAAAAA;
            }
            graphics.text(font, deltaStr, getColumnX(5), textY, deltaColor);
        }

        // Render scrollbar if content overflows viewport
        if (maxScroll > 0) {
            int scrollbarX = clipX2 - 4;
            int barHeight = Math.max(16, (int) ((float) viewportHeight / contentHeight * viewportHeight));
            int barY = rowsTop + (int) ((float) scrollAmount / maxScroll * (viewportHeight - barHeight));

            graphics.fill(scrollbarX, rowsTop, scrollbarX + 3, rowsBottom, 0xFF101014);
            graphics.fill(scrollbarX, barY, scrollbarX + 3, barY + barHeight, 0xFF555566);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int tableTop = this.guiTop + 76;
        int tableBottom = this.guiTop + this.guiHeight - 32;

        if (mouseY >= tableTop && mouseY <= tableBottom) {
            int contentHeight = records.size() * ROW_HEIGHT;
            int viewportHeight = tableBottom - tableTop;
            int maxScroll = Math.max(0, contentHeight - viewportHeight);

            if (maxScroll > 0) {
                scrollAmount -= verticalAmount * (ROW_HEIGHT * 1.5);
                scrollAmount = Math.clamp(scrollAmount, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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

    private static String formatActionName(byte actionCode) {
        ActionType type = ActionType.fromWireId(actionCode);
        return switch (type) {
            case PICKUP -> "Pickup";
            case PLACE -> "Place";
            case SHIFT_CLICK_EXTRACT -> "Shift Extract";
            case SHIFT_CLICK_INSERT -> "Shift Insert";
            case HOTBAR_SWAP -> "Hotbar Swap";
            case DRAG_SPLIT -> "Drag Split";
            case DOUBLE_CLICK_COLLECT -> "Collect";
            case HOPPER_EXTRACT -> "Hopper Out";
            case HOPPER_INSERT -> "Hopper In";
            case DROP_FROM_SLOT -> "Drop";
            case ROLLBACK_COMPENSATION -> "Rollback";
            default -> "Unknown";
        };
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 2)) + "..";
    }

    private static String cleanDimensionName(String dimension) {
        if (dimension == null) return "Overworld";
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "The Nether";
            case "minecraft:the_end" -> "The End";
            default -> dimension.replace("minecraft:", "");
        };
    }
}
