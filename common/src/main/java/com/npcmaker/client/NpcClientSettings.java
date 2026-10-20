package com.npcmaker.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.npcmaker.Constants;
import com.npcmaker.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Each player's own NPC Maker preferences, kept in {@code config/npcmaker-client.json}. For now: whether NPC
 * speech bubbles (approach and idle lines, hit warnings) are shown at all. Change with {@code /npcbubbles}.
 */
public final class NpcClientSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean showSpeechBubbles = true;
    private static boolean loaded;

    private NpcClientSettings() {
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-client.json");
    }

    public static boolean showSpeechBubbles() {
        load();
        return showSpeechBubbles;
    }

    /** Sets (or with null, toggles) the bubble setting, saves it, and returns a message for the player. */
    public static Component setSpeechBubbles(Boolean value) {
        load();
        showSpeechBubbles = value != null ? value : !showSpeechBubbles;
        save();
        return showSpeechBubbles
                ? Component.literal("NPC speech bubbles are now shown.").withStyle(ChatFormatting.GREEN)
                : Component.literal("NPC speech bubbles are now hidden. Use /npcbubbles on to show them again.").withStyle(ChatFormatting.GRAY);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("show_speech_bubbles")) {
                showSpeechBubbles = json.get("show_speech_bubbles").getAsBoolean();
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not read {}, using defaults", file, e);
        }
    }

    private static void save() {
        JsonObject json = new JsonObject();
        json.addProperty("show_speech_bubbles", showSpeechBubbles);
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not save {}", file(), e);
        }
    }
}
