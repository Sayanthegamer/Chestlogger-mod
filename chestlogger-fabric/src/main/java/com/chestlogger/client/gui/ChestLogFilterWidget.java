package com.chestlogger.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Filter bar widget allowing player and item search inputs with [Filter] and [Clear] actions.
 */
public class ChestLogFilterWidget extends AbstractWidget {
    private final EditBox playerBox;
    private final EditBox itemBox;
    private final Button btnFilter;
    private final Button btnClear;

    private final BiConsumer<String, String> onFilterApplied;
    private final Runnable onFilterCleared;

    public ChestLogFilterWidget(
            int x, int y, int width, int height,
            Font font,
            BiConsumer<String, String> onFilterApplied,
            Runnable onFilterCleared
    ) {
        super(x, y, width, height, Component.literal("Search & Filter Controls"));
        this.onFilterApplied = onFilterApplied;
        this.onFilterCleared = onFilterCleared;

        int boxHeight = 16;
        int btnHeight = 18;

        this.playerBox = new EditBox(font, x, y, 100, boxHeight, Component.literal("Filter Player"));
        this.playerBox.setMaxLength(32);
        this.playerBox.setHint(Component.literal("§8Player name..."));

        this.itemBox = new EditBox(font, x, y, 120, boxHeight, Component.literal("Filter Item"));
        this.itemBox.setMaxLength(64);
        this.itemBox.setHint(Component.literal("§8Item id (diamond)..."));

        this.btnFilter = Button.builder(Component.literal("Filter"), btn -> applyFilter())
                .bounds(x, y, 46, btnHeight)
                .tooltip(Tooltip.create(Component.literal("Apply player/item search filters")))
                .build();

        this.btnClear = Button.builder(Component.literal("Clear"), btn -> clearFilter())
                .bounds(x, y, 42, btnHeight)
                .tooltip(Tooltip.create(Component.literal("Clear search filters")))
                .build();

        updateLayout(x, y, width, height);
    }

    public void updateLayout(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);

        int curX = x + 2;
        int inputY = y + (height - 16) / 2;
        int btnY = y + (height - 18) / 2;

        // Player input (~110px)
        playerBox.setX(curX);
        playerBox.setY(inputY);
        playerBox.setWidth(110);
        curX += 114;

        // Item input (~130px)
        itemBox.setX(curX);
        itemBox.setY(inputY);
        itemBox.setWidth(130);
        curX += 134;

        // Action buttons
        btnFilter.setX(curX);
        btnFilter.setY(btnY);
        curX += 50;

        btnClear.setX(curX);
        btnClear.setY(btnY);
    }

    public void setFilters(String playerFilter, String itemFilter) {
        playerBox.setValue(playerFilter != null ? playerFilter : "");
        itemBox.setValue(itemFilter != null ? itemFilter : "");
    }

    public void applyFilter() {
        if (onFilterApplied != null) {
            onFilterApplied.accept(playerBox.getValue().trim(), itemBox.getValue().trim());
        }
    }

    public void clearFilter() {
        playerBox.setValue("");
        itemBox.setValue("");
        if (onFilterCleared != null) {
            onFilterCleared.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        playerBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        itemBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        btnFilter.extractRenderState(graphics, mouseX, mouseY, partialTick);
        btnClear.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isHovered) {
        if (playerBox.mouseClicked(event, isHovered)) return true;
        if (itemBox.mouseClicked(event, isHovered)) return true;
        if (btnFilter.mouseClicked(event, isHovered)) return true;
        if (btnClear.mouseClicked(event, isHovered)) return true;
        return super.mouseClicked(event, isHovered);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (playerBox.isFocused() || itemBox.isFocused()) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                applyFilter();
                return true;
            }
            if (playerBox.keyPressed(event)) return true;
            if (itemBox.keyPressed(event)) return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (playerBox.charTyped(event)) return true;
        if (itemBox.charTyped(event)) return true;
        return super.charTyped(event);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, Component.literal("Search filters"));
    }

    public List<AbstractWidget> getChildren() {
        return List.of(playerBox, itemBox, btnFilter, btnClear);
    }
}
