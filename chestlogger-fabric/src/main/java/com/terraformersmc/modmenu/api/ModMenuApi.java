package com.terraformersmc.modmenu.api;

/**
 * Standard Mod Menu API interface.
 */
public interface ModMenuApi {

    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return null;
    }
}
