package com.chestlogger.client.gui;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.network.ChestLogProvenancePayload;
import com.chestlogger.network.ProvenanceDisplayNode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 100% custom, zero-dependency client-side Item Provenance & Chain-of-Custody viewer (Minecraft 26.2).
 */
public class ChestLogProvenanceScreen extends Screen {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int ROW_HEIGHT = 22;

    private final ChestLogProvenancePayload payload;

    // Layout Bounds
    private int guiLeft;
    private int guiTop;
    private int guiWidth;
    private int guiHeight;

    // Scrolling State
    private double scrollAmount = 0.0;

    // Hover Tooltips
    private ItemStack hoveredItemTooltip = null;
    private Component hoveredTooltip = null;
    private int tooltipMouseX = 0;
    private int tooltipMouseY = 0;

    public ChestLogProvenanceScreen(ChestLogProvenancePayload payload) {
        super(Component.literal("Item Provenance Trace"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        super.init();

        this.guiWidth = Math.min(480, this.width - 20);
        this.guiHeight = Math.min(270, this.height - 20);
        this.guiLeft = (this.width - this.guiWidth) / 2;
        this.guiTop = (this.height - this.guiHeight) / 2;

        int footerY = this.guiTop + this.guiHeight - 24;
        addRenderableWidget(Button.builder(Component.literal("Close"), btn -> onClose())
                .bounds(this.guiLeft + (this.guiWidth - 80) / 2, footerY, 80, 18)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredItemTooltip = null;
        this.hoveredTooltip = null;

        // 1. Background Panel
        renderPanelBackground(graphics);

        // 2. Header
        renderHeader(graphics);

        // 3. Table Column Headers
        int tableHeadersY = this.guiTop + 40;
        renderTableHeaders(graphics, tableHeadersY);

        // 4. Node Rows Viewport
        int rowsTop = tableHeadersY + 14;
        int rowsBottom = this.guiTop + this.guiHeight - 28;
        int viewportHeight = rowsBottom - rowsTop;

        renderRows(graphics, mouseX, mouseY, rowsTop, rowsBottom, viewportHeight);

        // 5. Child widgets (Close button)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // 6. Hovered tooltips
        if (hoveredItemTooltip != null && !hoveredItemTooltip.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, hoveredItemTooltip, tooltipMouseX, tooltipMouseY);
        } else if (hoveredTooltip != null) {
            graphics.setTooltipForNextFrame(hoveredTooltip, tooltipMouseX, tooltipMouseY);
        }
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

        graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF000000);
        graphics.fill(x1, y1, x2, y2, 0xEE16161C);
        graphics.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 38, 0xFF202028);

        graphics.fill(x1 + 4, y1 + 38, x2 - 4, y1 + 39, 0xFF383844);
        graphics.fill(x1 + 4, y2 - 27, x2 - 4, y2 - 26, 0xFF2A2A34);
    }

    private void renderHeader(GuiGraphicsExtractor graphics) {
        Font font = this.font;
        int x = this.guiLeft + 8;
        int y = this.guiTop + 6;

        String title = "§6§lChestLogger §fChain-of-Custody Trace";
        graphics.text(font, title, x, y, 0xFFFFFFFF);

        String confidenceBadge = formatConfidenceBadge(payload.overallConfidence());
        int titleWidth = font.width(title);
        graphics.text(font, confidenceBadge, x + titleWidth + 8, y, 0xFFFFFFFF);

        // Subtitle line
        int line2Y = y + 14;
        ItemStack targetStack = ItemResolver.resolve(payload.targetItemId());
        if (!targetStack.isEmpty()) {
            graphics.item(targetStack, x, line2Y - 3);
        }

        String itemLabel = "§e" + ItemResolver.getDisplayName(payload.targetItemId()).getString();
        graphics.text(font, itemLabel, x + 18, line2Y, 0xFFFFFF55);

        String stepsLabel = String.format("§7Total Steps: §a%d", payload.totalSteps());
        int stepsWidth = font.width(stepsLabel);
        graphics.text(font, stepsLabel, this.guiLeft + this.guiWidth - stepsWidth - 10, line2Y, 0xFFAAAAAA);
    }

    private void renderTableHeaders(GuiGraphicsExtractor graphics, int y) {
        Font font = this.font;
        int x1 = this.guiLeft + 6;
        int x2 = this.guiLeft + this.guiWidth - 6;

        graphics.fill(x1, y, x2, y + 13, 0xFF202028);

        int textY = y + 3;
        graphics.text(font, "§7Step", getColumnX(0), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Action", getColumnX(1), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Confidence", getColumnX(2), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Actor", getColumnX(3), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Item & Delta", getColumnX(4), textY, 0xFFB0B0B0);
        graphics.text(font, "§7Time & Position", getColumnX(5), textY, 0xFFB0B0B0);
    }

    private int getColumnX(int col) {
        int left = this.guiLeft + 8;
        return switch (col) {
            case 0 -> left;              // Step (#1)
            case 1 -> left + 36;         // Action (TAKE/PUT)
            case 2 -> left + 90;         // Confidence ([HIGH])
            case 3 -> left + 155;        // Actor (PlayerName)
            case 4 -> left + 245;        // Item & Delta (+64 Diamond)
            case 5 -> left + 355;        // Time & Position
            default -> left;
        };
    }

    private void renderRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int rowsTop, int rowsBottom, int viewportHeight) {
        List<ProvenanceDisplayNode> nodes = payload.nodes();
        if (nodes.isEmpty()) {
            String emptyMsg = "§7No chain-of-custody provenance records found.";
            int msgWidth = this.font.width(emptyMsg);
            graphics.text(this.font, emptyMsg, this.guiLeft + (this.guiWidth - msgWidth) / 2, rowsTop + viewportHeight / 2 - 4, 0xFF888888);
            return;
        }

        int contentHeight = nodes.size() * ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        int clipX1 = this.guiLeft + 6;
        int clipX2 = this.guiLeft + this.guiWidth - 6;

        Font font = this.font;

        for (int i = 0; i < nodes.size(); i++) {
            ProvenanceDisplayNode node = nodes.get(i);
            int rowY = rowsTop + (i * ROW_HEIGHT) - (int) scrollAmount;

            if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) {
                continue;
            }

            boolean isHovered = mouseX >= clipX1 && mouseX <= clipX2 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseY >= rowsTop && mouseY <= rowsBottom;

            int rowBg = isHovered ? 0xFF282834 : ((i % 2 == 0) ? 0xFF1A1A22 : 0xFF16161E);
            graphics.fill(clipX1, rowY, clipX2, rowY + ROW_HEIGHT - 1, rowBg);

            int textY = rowY + 6;

            // 1. Step
            graphics.text(font, "§e#" + (node.stepIndex() + 1), getColumnX(0), textY, 0xFFFFFF55);

            // 2. Action
            graphics.text(font, "§f" + node.actionType(), getColumnX(1), textY, 0xFFFFFFFF);

            // 3. Confidence Badge
            graphics.text(font, formatConfidenceBadge(node.confidence()), getColumnX(2), textY, 0xFFFFFFFF);

            // 4. Actor
            graphics.text(font, "§b" + truncate(node.actorName(), 12), getColumnX(3), textY, 0xFF55FFFF);

            // 5. Item & Delta
            int itemColX = getColumnX(4);
            ItemStack stack = ItemResolver.resolve(node.itemId());
            if (!stack.isEmpty()) {
                graphics.item(stack, itemColX, rowY + 3);
                if (isHovered && mouseX >= itemColX && mouseX <= itemColX + 16) {
                    this.hoveredItemTooltip = stack;
                    this.tooltipMouseX = mouseX;
                    this.tooltipMouseY = mouseY;
                }
            }

            int delta = node.deltaQuantity();
            String deltaStr = (delta > 0 ? "§a+" : "§c") + delta;
            graphics.text(font, deltaStr, itemColX + 18, textY, delta > 0 ? 0xFF55FF55 : 0xFFFF5555);

            // 6. Time & Position
            int timeX = getColumnX(5);
            String timeStr = formatTimestamp(node.timestampMs());
            graphics.text(font, "§7" + timeStr, timeX, textY, 0xFFAAAAAA);

            if (isHovered && node.notes() != null && !node.notes().isBlank()) {
                int[] coords = BlockPosUtil.unpack(node.packedPos());
                this.hoveredTooltip = Component.literal(String.format("§eStep #%d\n§7Pos: §b%d, %d, %d (%s)\n§8%s",
                        node.stepIndex() + 1, coords[0], coords[1], coords[2], node.dimension(), node.notes()));
                this.tooltipMouseX = mouseX;
                this.tooltipMouseY = mouseY;
            }
        }

        // Scrollbar
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
        int tableTop = this.guiTop + 54;
        int tableBottom = this.guiTop + this.guiHeight - 28;

        if (mouseY >= tableTop && mouseY <= tableBottom) {
            int contentHeight = payload.nodes().size() * ROW_HEIGHT;
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

    private static String formatConfidenceBadge(String confidence) {
        if (confidence == null) return "§7[UNKNOWN]";
        String upper = confidence.toUpperCase();
        if (upper.contains("EXACT")) return "§a[EXACT_LINKAGE]";
        if (upper.contains("HIGH")) return "§e[HIGH_CONFIDENCE]";
        if (upper.contains("PROBABLE") || upper.contains("MEDIUM")) return "§6[PROBABLE]";
        return "§7[" + upper + "]";
    }

    private static String formatTimestamp(long timestampMs) {
        try {
            return TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMs));
        } catch (Exception e) {
            return "00:00:00";
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text;
    }
}
