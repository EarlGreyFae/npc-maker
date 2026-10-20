package com.npcmaker.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.npcmaker.Constants;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ready-made AI templates (guard, townsfolk, bandit, companion), bundled in the jar under
 * {@code npcmaker_presets/}. An NPC file with {@code "ai_template": "bandit"} gets the bandit settings as a base,
 * and anything the file sets itself wins (objects are merged key by key; lists and values are replaced).
 */
public final class NpcPresets {

    public static final List<String> NAMES = List.of("guard", "townsfolk", "bandit", "companion");

    private static final Map<String, Optional<JsonObject>> CACHE = new HashMap<>();

    private NpcPresets() {
    }

    public static Optional<JsonObject> get(String name) {
        return CACHE.computeIfAbsent(name, NpcPresets::load).map(JsonObject::deepCopy);
    }

    private static Optional<JsonObject> load(String name) {
        try (InputStream in = NpcPresets.class.getResourceAsStream("/npcmaker_presets/" + name + ".json")) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            Constants.LOG.error("Could not read AI template '{}'", name, e);
            return Optional.empty();
        }
    }

    /** Applied to every NPC file before it's read: merges the named AI template underneath it. */
    public static JsonElement applyTemplate(JsonElement file) {
        if (!file.isJsonObject() || !file.getAsJsonObject().has("ai_template")) {
            return file;
        }
        String name = file.getAsJsonObject().get("ai_template").getAsString();
        Optional<JsonObject> preset = get(name);
        if (preset.isEmpty()) {
            Constants.LOG.warn("Unknown ai_template '{}'. Known: {}", name, NAMES);
            return file;
        }
        return merge(preset.get(), file.getAsJsonObject());
    }

    private static JsonObject merge(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            JsonElement existing = result.get(entry.getKey());
            if (existing != null && existing.isJsonObject() && entry.getValue().isJsonObject()) {
                result.add(entry.getKey(), merge(existing.getAsJsonObject(), entry.getValue().getAsJsonObject()));
            } else {
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }
}
