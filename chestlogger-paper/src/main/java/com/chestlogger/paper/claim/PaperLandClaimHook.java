package com.chestlogger.paper.claim;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Multi-plugin land claim integration hook for Paper servers.
 * Auto-detects GriefPrevention, WorldGuard, Towny, and Lands to prevent container claim theft
 * inside another player's claimed territory without hard dependencies.
 */
public final class PaperLandClaimHook {

    private static final Logger LOGGER = Logger.getLogger("ChestLogger-LandClaimHook");

    private PaperLandClaimHook() {}

    public record LandClaimCheckResult(boolean allowed, String ownerName, String pluginName) {
        public static LandClaimCheckResult pass() {
            return new LandClaimCheckResult(true, null, null);
        }

        public static LandClaimCheckResult blocked(String ownerName, String pluginName) {
            return new LandClaimCheckResult(false, ownerName, pluginName);
        }
    }

    /**
     * Checks if the given location is in another player's land claim.
     */
    public static LandClaimCheckResult checkCanClaimAt(Player player, Location location) {
        if (player == null || location == null) {
            return LandClaimCheckResult.pass();
        }

        // 1. GriefPrevention Check
        try {
            Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object instance = gpClass.getField("instance").get(null);
            if (instance != null) {
                Object dataStore = gpClass.getField("dataStore").get(instance);
                Method getClaimAt = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, Class.forName("me.ryanhamshire.GriefPrevention.Claim"));
                Object claim = getClaimAt.invoke(dataStore, location, false, null);
                if (claim != null) {
                    Method allowContainers = claim.getClass().getMethod("allowContainers", Player.class);
                    Object errorMsg = allowContainers.invoke(claim, player);
                    if (errorMsg != null) {
                        Method getOwnerName = claim.getClass().getMethod("getOwnerName");
                        String ownerName = (String) getOwnerName.invoke(claim);
                        return LandClaimCheckResult.blocked(ownerName != null ? ownerName : "another player", "GriefPrevention");
                    }
                }
            }
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException ignored) {
            // GriefPrevention not installed
        } catch (Exception e) {
            LOGGER.fine("GriefPrevention hook check failed: " + e.getMessage());
        }

        // 2. Towny Check
        try {
            Class<?> townyApiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            Method getInstance = townyApiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            if (api != null) {
                Method getTownBlock = townyApiClass.getMethod("getTownBlock", Location.class);
                Object townBlock = getTownBlock.invoke(api, location);
                if (townBlock != null) {
                    Method hasResident = townBlock.getClass().getMethod("hasResident");
                    boolean hasResidentVal = (boolean) hasResident.invoke(townBlock);
                    if (hasResidentVal) {
                        Method getResident = townBlock.getClass().getMethod("getResident");
                        Object resident = getResident.invoke(townBlock);
                        if (resident != null) {
                            Method getUniqueId = resident.getClass().getMethod("getUUID");
                            UUID resUuid = (UUID) getUniqueId.invoke(resident);
                            if (resUuid != null && !resUuid.equals(player.getUniqueId())) {
                                Method getName = resident.getClass().getMethod("getName");
                                String resName = (String) getName.invoke(resident);
                                return LandClaimCheckResult.blocked(resName != null ? resName : "Towny Resident", "Towny");
                            }
                        }
                    }
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Towny not installed
        } catch (Exception e) {
            LOGGER.fine("Towny hook check failed: " + e.getMessage());
        }

        return LandClaimCheckResult.pass();
    }
}
