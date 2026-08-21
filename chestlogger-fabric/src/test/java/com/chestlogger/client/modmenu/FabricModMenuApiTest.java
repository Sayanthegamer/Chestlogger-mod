package com.chestlogger.client.modmenu;

import com.chestlogger.client.gui.ChestLogConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Fabric Mod Menu API Integration Tests")
class FabricModMenuApiTest {

    @Test
    @DisplayName("ChestLoggerModMenu should implement ModMenuApi and provide ChestLogConfigScreen factory")
    void testModMenuApiConfigScreenFactory() throws Exception {
        ChestLoggerModMenu modMenu = new ChestLoggerModMenu();
        assertThat(modMenu).isInstanceOf(ModMenuApi.class);

        ConfigScreenFactory<?> factory = modMenu.getModConfigScreenFactory();
        assertThat(factory).as("getModConfigScreenFactory must not be null").isNotNull();

        // Verify ChestLogConfigScreen extends Minecraft Screen
        assertThat(Screen.class.isAssignableFrom(ChestLogConfigScreen.class))
                .as("ChestLogConfigScreen must extend Screen")
                .isTrue();

        // Verify ChestLogConfigScreen has parent Screen constructors
        assertThat(ChestLogConfigScreen.class.getConstructor(Screen.class))
                .as("ChestLogConfigScreen must have (Screen parent) constructor")
                .isNotNull();

        assertThat(ChestLogConfigScreen.class.getConstructor(Screen.class, com.chestlogger.network.ChestLogConfigPayload.class))
                .as("ChestLogConfigScreen must have (Screen parent, ChestLogConfigPayload payload) constructor")
                .isNotNull();
    }
}
