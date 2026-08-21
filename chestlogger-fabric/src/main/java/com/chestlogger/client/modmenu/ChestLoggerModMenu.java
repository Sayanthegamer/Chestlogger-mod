package com.chestlogger.client.modmenu;

import com.chestlogger.client.gui.ChestLogConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration for ChestLogger providing direct access to the in-game configuration screen.
 */
public class ChestLoggerModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ChestLogConfigScreen::new;
    }
}
