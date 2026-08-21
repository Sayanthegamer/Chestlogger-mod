package com.chestlogger.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Fabric Mod Menu Metadata & Icon Tests")
class FabricModMenuMetadataTest {

    @Test
    @DisplayName("fabric.mod.json should be present and contain required Mod Menu metadata")
    void testFabricModJsonStructure() {
        InputStream stream = getClass().getResourceAsStream("/fabric.mod.json");
        assertThat(stream).as("fabric.mod.json must exist in resources").isNotNull();

        JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();

        assertThat(root.get("id").getAsString()).isEqualTo("chestlogger");
        assertThat(root.get("name").getAsString()).isEqualTo("ChestLogger");
        assertThat(root.has("description")).isTrue();
        assertThat(root.get("description").getAsString()).isNotEmpty();

        // Icon registration
        assertThat(root.has("icon")).as("fabric.mod.json must specify an icon").isTrue();
        assertThat(root.get("icon").getAsString()).isEqualTo("assets/chestlogger/icon.png");

        // Authors
        assertThat(root.has("authors")).isTrue();
        JsonArray authors = root.getAsJsonArray("authors");
        assertThat(authors).extracting(e -> e.getAsString()).contains("Sayanthegamer");

        // Contact information
        assertThat(root.has("contact")).isTrue();
        JsonObject contact = root.getAsJsonObject("contact");
        assertThat(contact.has("homepage")).isTrue();
        assertThat(contact.has("sources")).isTrue();
        assertThat(contact.has("issues")).as("Issues link is required for Mod Menu issue reporting").isTrue();
        assertThat(contact.get("issues").getAsString()).contains("issues");

        // License
        assertThat(root.has("license")).isTrue();
        assertThat(root.get("license").getAsString()).isEqualTo("MIT");

        // Entrypoints: modmenu
        assertThat(root.has("entrypoints")).isTrue();
        JsonObject entrypoints = root.getAsJsonObject("entrypoints");
        assertThat(entrypoints.has("modmenu")).as("modmenu entrypoint must be registered").isTrue();
        JsonArray modmenu = entrypoints.getAsJsonArray("modmenu");
        assertThat(modmenu).extracting(e -> e.getAsString()).contains("com.chestlogger.client.modmenu.ChestLoggerModMenu");
    }

    @Test
    @DisplayName("assets/chestlogger/icon.png should exist, have valid PNG header, and readable dimensions")
    void testIconAssetValidity() throws Exception {
        InputStream iconStream = getClass().getResourceAsStream("/assets/chestlogger/icon.png");
        assertThat(iconStream).as("icon.png must be present at assets/chestlogger/icon.png").isNotNull();

        BufferedImage image = ImageIO.read(iconStream);
        assertThat(image).as("icon.png must be a valid readable image").isNotNull();
        assertThat(image.getWidth()).as("icon width must be >= 64px").isGreaterThanOrEqualTo(64);
        assertThat(image.getHeight()).as("icon height must be >= 64px").isGreaterThanOrEqualTo(64);
    }
}
