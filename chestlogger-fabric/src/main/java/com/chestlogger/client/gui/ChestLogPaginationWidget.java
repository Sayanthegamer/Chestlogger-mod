package com.chestlogger.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Modular pagination control bar for ChestLogger GUI.
 * Provides [|<], [<], Page X / Y indicator, [>], [>|], [Refresh], and [Close] controls.
 */
public class ChestLogPaginationWidget extends AbstractWidget {
    private int currentPage = 1;
    private int totalPages = 1;

    private Consumer<Integer> onPageChange;
    private Runnable onRefresh;
    private Runnable onClose;

    private final Button btnFirst;
    private final Button btnPrev;
    private final Button btnNext;
    private final Button btnLast;
    private final Button btnRefresh;
    private final Button btnClose;

    private final List<Button> allButtons;

    public ChestLogPaginationWidget(
            int x, int y, int width, int height,
            Consumer<Integer> onPageChange,
            Runnable onRefresh,
            Runnable onClose
    ) {
        super(x, y, width, height, Component.literal("Pagination Controls"));
        this.onPageChange = onPageChange;
        this.onRefresh = onRefresh;
        this.onClose = onClose;

        this.btnFirst = Button.builder(Component.literal("|<"), btn -> handleFirstPage())
                .bounds(x, y, 20, 20)
                .tooltip(Tooltip.create(Component.literal("First Page")))
                .build();

        this.btnPrev = Button.builder(Component.literal("<"), btn -> handlePrevPage())
                .bounds(x, y, 20, 20)
                .tooltip(Tooltip.create(Component.literal("Previous Page")))
                .build();

        this.btnNext = Button.builder(Component.literal(">"), btn -> handleNextPage())
                .bounds(x, y, 20, 20)
                .tooltip(Tooltip.create(Component.literal("Next Page")))
                .build();

        this.btnLast = Button.builder(Component.literal(">|"), btn -> handleLastPage())
                .bounds(x, y, 20, 20)
                .tooltip(Tooltip.create(Component.literal("Last Page")))
                .build();

        this.btnRefresh = Button.builder(Component.literal("Refresh"), btn -> handleRefresh())
                .bounds(x, y, 55, 20)
                .tooltip(Tooltip.create(Component.literal("Refresh Current Page")))
                .build();

        this.btnClose = Button.builder(Component.literal("Close"), btn -> handleClose())
                .bounds(x, y, 50, 20)
                .tooltip(Tooltip.create(Component.literal("Close Log Screen")))
                .build();

        this.allButtons = List.of(btnFirst, btnPrev, btnNext, btnLast, btnRefresh, btnClose);

        updateLayout(x, y, width, height);
        updateButtonStates();
    }

    public void updateLayout(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);

        int btnY = y + (height - 20) / 2;

        // Navigation cluster on the left
        int navStartX = x + 4;
        btnFirst.setX(navStartX);
        btnFirst.setY(btnY);

        btnPrev.setX(navStartX + 22);
        btnPrev.setY(btnY);

        int labelWidth = 76;
        int nextStartX = navStartX + 22 + 20 + labelWidth;

        btnNext.setX(nextStartX);
        btnNext.setY(btnY);

        btnLast.setX(nextStartX + 22);
        btnLast.setY(btnY);

        // Action buttons aligned to the right
        int closeX = x + width - 54;
        btnClose.setX(closeX);
        btnClose.setY(btnY);

        int refreshX = closeX - 58;
        btnRefresh.setX(refreshX);
        btnRefresh.setY(btnY);
    }

    public void setPageInfo(int currentPage, int totalPages) {
        this.currentPage = Math.max(1, currentPage);
        this.totalPages = Math.max(1, totalPages);
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean canGoBack = currentPage > 1;
        boolean canGoForward = currentPage < totalPages;

        btnFirst.active = canGoBack;
        btnPrev.active = canGoBack;
        btnNext.active = canGoForward;
        btnLast.active = canGoForward;
    }

    private void handleFirstPage() {
        if (currentPage > 1 && onPageChange != null) {
            onPageChange.accept(1);
        }
    }

    private void handlePrevPage() {
        if (currentPage > 1 && onPageChange != null) {
            onPageChange.accept(currentPage - 1);
        }
    }

    private void handleNextPage() {
        if (currentPage < totalPages && onPageChange != null) {
            onPageChange.accept(currentPage + 1);
        }
    }

    private void handleLastPage() {
        if (currentPage < totalPages && onPageChange != null) {
            onPageChange.accept(totalPages);
        }
    }

    private void handleRefresh() {
        if (onRefresh != null) {
            onRefresh.run();
        }
    }

    private void handleClose() {
        if (onClose != null) {
            onClose.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Render child buttons
        for (Button btn : allButtons) {
            btn.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        // Render Page X / Y text centered between Prev and Next buttons
        Font font = Minecraft.getInstance().font;
        String pageText = String.format("§fPage §e%d §7/ §e%d", currentPage, totalPages);

        int textStartX = btnPrev.getX() + btnPrev.getWidth() + 2;
        int textEndX = btnNext.getX() - 2;
        int textCenterX = (textStartX + textEndX) / 2;
        int textY = getY() + (getHeight() - 8) / 2;

        graphics.centeredText(font, pageText, textCenterX, textY, 0xFFE0E0E0);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isHovered) {
        for (Button btn : allButtons) {
            if (btn.mouseClicked(event, isHovered)) {
                return true;
            }
        }
        return super.mouseClicked(event, isHovered);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, Component.literal("Page " + currentPage + " of " + totalPages));
    }

    public List<Button> getButtons() {
        return allButtons;
    }
}
