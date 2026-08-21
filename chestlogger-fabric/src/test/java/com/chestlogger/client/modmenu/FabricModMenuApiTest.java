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
    void testModMenuApiConfigScreenFactory() {
        ChestLoggerModMenu modMenu = new ChestLoggerModMenu();
        assertThat(modMenu).isInstanceOf(ModMenuApi.class);

        ConfigScreenFactory<?> factory = modMenu.getModConfigScreenFactory();
        assertThat(factory).as("getModConfigScreenFactory must not be null").isNotNull();

        Screen screen = factory.create(null);
        assertThat(screen).as("Factory must produce an instance of ChestLogConfigScreen")
                .isInstanceOf(ChestLogConfigScreen.class);
    }
}
