package com.npcmaker.custom;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.npcmaker.Constants;
import com.npcmaker.NpcPermissions;
import com.npcmaker.content.NpcContent;
import com.npcmaker.network.CustomPayloads;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The world's custom content: sounds (.ogg), particle textures (.png), Blockbench models (.geo.json + .png) and
 * animations (.animation.json), kept in {@code <world>/npcmaker/custom/}. Builders upload files from the editor; the
 * server checks them, stores them, and sends every player the list (the manifest). Each player's game downloads what
 * it hasn't cached and builds a resource pack from it, so no web hosting is needed.
 *
 * <p>Custom particles play through one of 32 particle slots NPC Maker registers ({@code npcmaker:custom_0..31}); the
 * slot a particle gets is remembered in {@code particles/slots.json}. NPC files refer to custom things by name:
 * {@code npcmaker_custom:<name>} for sounds, particles and models, {@code custom:<animation name>} for animations.
 */
public final class CustomContent {

    public static final String NAMESPACE = "npcmaker_custom";
    public static final String ANIMATION_PREFIX = "custom:";
    public static final int PARTICLE_SLOTS = 32;

    private static final int MAX_SOUND = 4 * 1024 * 1024;
    private static final int MAX_PNG = 1024 * 1024;
    private static final int MAX_JSON = 2 * 1024 * 1024;
    private static final long MAX_TOTAL = 96L * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private static @Nullable Path dir;
    /** Manifest path ("sounds/bell.ogg") → entry. */
    private static final Map<String, CustomPayloads.FileEntry> FILES = new TreeMap<>();
    private static final Map<String, Path> BY_SHA = new HashMap<>();
    private static final Map<String, Integer> PARTICLE_SLOT = new TreeMap<>();
    /** Animation name → its clip (for lengths on the server). */
    private static final Map<String, AnimationClip> ANIMATIONS = new HashMap<>();

    private CustomContent() {
    }

    // ---- loading ------------------------------------------------------------------------------------------

    public static void init(MinecraftServer server) {
        dir = NpcContent.root(server).resolve("custom");
        FILES.clear();
        BY_SHA.clear();
        PARTICLE_SLOT.clear();
        ANIMATIONS.clear();
        try {
            for (String sub : List.of("sounds", "particles", "models", "animations")) {
                Files.createDirectories(dir.resolve(sub));
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String path = dir.relativize(file).toString().replace('\\', '/');
                    if (path.equals("particles/slots.json") || kindOf(path) == null) {
                        continue;
                    }
                    index(path, Files.readAllBytes(file));
                }
            }
            loadSlots();
        } catch (IOException e) {
            Constants.LOG.error("Could not read custom content in {}", dir, e);
        }
        Constants.LOG.info("Loaded {} custom content file(s)", FILES.size());
    }

    private static void index(String path, byte[] data) {
        String sha = sha1(data);
        FILES.put(path, new CustomPayloads.FileEntry(path, sha, data.length));
        BY_SHA.put(sha, dir.resolve(path));
        if (path.startsWith("animations/")) {
            try {
                JsonElement json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                if (json.isJsonObject()) {
                    for (AnimationClip clip : AnimationClip.readFile(json.getAsJsonObject())) {
                        ANIMATIONS.put(clip.name(), clip);
                    }
                }
            } catch (Exception e) {
                Constants.LOG.warn("Custom animation file {} couldn't be read: {}", path, e.getMessage());
            }
        }
    }

    private static void loadSlots() throws IOException {
        Path slots = dir.resolve("particles/slots.json");
        if (Files.exists(slots)) {
            try (Reader r = Files.newBufferedReader(slots)) {
                JsonElement json = JsonParser.parseReader(r);
                if (json.isJsonObject()) {
                    json.getAsJsonObject().entrySet().forEach(e -> PARTICLE_SLOT.put(e.getKey(), e.getValue().getAsInt()));
                }
            }
        }
        // drop slots whose texture is gone, give new textures a slot
        PARTICLE_SLOT.keySet().removeIf(name -> !FILES.containsKey("particles/" + name + ".png"));
        for (String path : FILES.keySet()) {
            if (path.startsWith("particles/")) {
                assignSlot(baseName(path));
            }
        }
        saveSlots();
    }

    private static boolean assignSlot(String name) {
        if (PARTICLE_SLOT.containsKey(name)) {
            return true;
        }
        for (int slot = 0; slot < PARTICLE_SLOTS; slot++) {
            if (!PARTICLE_SLOT.containsValue(slot)) {
                PARTICLE_SLOT.put(name, slot);
                return true;
            }
        }
        return false;
    }

    private static void saveSlots() throws IOException {
        JsonObject json = new JsonObject();
        PARTICLE_SLOT.forEach(json::addProperty);
        Files.writeString(dir.resolve("particles/slots.json"), GSON.toJson(json));
    }

    // ---- lookups for game logic ---------------------------------------------------------------------------

    /** The particle type a custom particle plays through ({@code npcmaker:custom_N}), or null. */
    public static @Nullable Identifier particleType(String name) {
        Integer slot = PARTICLE_SLOT.get(name);
        return slot == null ? null : Constants.id("custom_" + slot);
    }

    /** A custom animation by id ("custom:animation.baker.wave"), or null. */
    public static @Nullable AnimationClip animation(String id) {
        return id.startsWith(ANIMATION_PREFIX) ? ANIMATIONS.get(id.substring(ANIMATION_PREFIX.length())) : null;
    }

    public static CustomPayloads.Manifest manifest() {
        List<CustomPayloads.Slot> slots = new ArrayList<>();
        PARTICLE_SLOT.forEach((name, slot) -> slots.add(new CustomPayloads.Slot(name, slot)));
        return new CustomPayloads.Manifest(List.copyOf(FILES.values()), slots);
    }

    // ---- networking ---------------------------------------------------------------------------------------

    public static void sendManifest(ServerPlayer player) {
        NpcNetworking.sendToPlayer(player, manifest());
    }

    private static void broadcast(MinecraftServer server) {
        CustomPayloads.Manifest manifest = manifest();
        server.getPlayerList().getPlayers().forEach(p -> NpcNetworking.sendToPlayer(p, manifest));
    }

    /** Sends the files a player asked for, in pieces. Any player may ask (they need the files to see NPCs). */
    public static void sendFiles(ServerPlayer player, List<String> sha1s) {
        for (String sha : sha1s) {
            Path file = BY_SHA.get(sha);
            if (file == null) {
                continue;
            }
            try {
                byte[] data = Files.readAllBytes(file);
                int parts = Math.max(1, (data.length + CustomPayloads.DOWN_CHUNK - 1) / CustomPayloads.DOWN_CHUNK);
                for (int part = 0; part < parts; part++) {
                    int from = part * CustomPayloads.DOWN_CHUNK;
                    byte[] piece = Arrays.copyOfRange(data, from, Math.min(data.length, from + CustomPayloads.DOWN_CHUNK));
                    NpcNetworking.sendToPlayer(player, new CustomPayloads.FilePart(sha, part, parts, piece));
                }
            } catch (IOException e) {
                Constants.LOG.error("Could not send custom file {}", file, e);
            }
        }
    }

    // ---- uploads ------------------------------------------------------------------------------------------

    private record Upload(String fileName, byte[][] parts) {
    }

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

    public static void receivePart(ServerPlayer player, CustomPayloads.UploadPart part) {
        if (!NpcPermissions.canEdit(player) || dir == null || part.parts() <= 0 || part.parts() > 200
                || part.part() < 0 || part.part() >= part.parts()) {
            return;
        }
        Upload upload = UPLOADS.get(player.getUUID());
        if (upload == null || !upload.fileName().equals(part.fileName()) || upload.parts().length != part.parts()) {
            upload = new Upload(part.fileName(), new byte[part.parts()][]);
            UPLOADS.put(player.getUUID(), upload);
        }
        upload.parts()[part.part()] = part.data();
        for (byte[] piece : upload.parts()) {
            if (piece == null) {
                return;
            }
        }
        UPLOADS.remove(player.getUUID());
        int total = 0;
        for (byte[] piece : upload.parts()) {
            total += piece.length;
        }
        byte[] data = new byte[total];
        int at = 0;
        for (byte[] piece : upload.parts()) {
            System.arraycopy(piece, 0, data, at, piece.length);
            at += piece.length;
        }
        store(player, upload.fileName(), data);
    }

    /** Checks an uploaded file and stores it under the right folder; tells the builder how it went. */
    private static void store(ServerPlayer player, String fileName, byte[] data) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String path;
        String problem = null;
        if (lower.endsWith(".ogg")) {
            path = "sounds/" + slug(fileName, ".ogg") + ".ogg";
            problem = data.length > MAX_SOUND ? "Sounds can be up to 4 MB."
                    : !startsWith(data, "OggS") ? "That isn't an Ogg Vorbis sound (.ogg)." : null;
        } else if (lower.endsWith(".geo.json")) {
            path = "models/" + slug(fileName, ".geo.json") + ".geo.json";
            problem = data.length > MAX_JSON ? "Model files can be up to 2 MB." : checkGeometry(data);
        } else if (lower.endsWith(".animation.json")) {
            path = "animations/" + slug(fileName, ".animation.json") + ".animation.json";
            problem = data.length > MAX_JSON ? "Animation files can be up to 2 MB." : checkAnimations(data);
        } else if (lower.endsWith(".png")) {
            String name = slug(fileName, ".png");
            // A PNG named like an uploaded model is that model's texture; any other PNG is a particle.
            path = FILES.containsKey("models/" + name + ".geo.json") ? "models/" + name + ".png" : "particles/" + name + ".png";
            problem = data.length > MAX_PNG ? "Pictures can be up to 1 MB."
                    : !(data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G')
                    ? "That isn't a PNG picture." : null;
            if (problem == null && path.startsWith("particles/") && !PARTICLE_SLOT.containsKey(name)
                    && PARTICLE_SLOT.size() >= PARTICLE_SLOTS) {
                problem = "All " + PARTICLE_SLOTS + " custom particle slots are used. Delete one first.";
            }
        } else {
            reply(player, false, "NPC Maker takes .ogg sounds, .png pictures, .geo.json models and .animation.json animations.");
            return;
        }
        long total = FILES.values().stream().filter(f -> !f.path().equals(path)).mapToLong(CustomPayloads.FileEntry::size).sum();
        if (problem == null && total + data.length > MAX_TOTAL) {
            problem = "The world's custom content is full (96 MB). Delete something first.";
        }
        if (problem != null) {
            reply(player, false, fileName + ": " + problem);
            return;
        }
        try {
            Path target = dir.resolve(path);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            CustomPayloads.FileEntry old = FILES.get(path);
            if (old != null) {
                BY_SHA.remove(old.sha1());
            }
            index(path, data);
            if (path.startsWith("particles/")) {
                assignSlot(baseName(path));
                saveSlots();
            }
        } catch (IOException e) {
            reply(player, false, fileName + ": not saved (" + e.getMessage() + ").");
            return;
        }
        reply(player, true, "Added " + describe(path) + ". Everyone's game is loading it.");
        broadcast(player.level().getServer());
    }

    public static void delete(ServerPlayer player, String path) {
        if (!NpcPermissions.canEdit(player) || dir == null || !FILES.containsKey(path)) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(path));
            CustomPayloads.FileEntry old = FILES.remove(path);
            BY_SHA.remove(old.sha1());
            if (path.startsWith("particles/")) {
                PARTICLE_SLOT.remove(baseName(path));
                saveSlots();
            }
            if (path.startsWith("animations/")) {
                rebuildAnimations();
            }
        } catch (IOException e) {
            reply(player, false, "Couldn't delete " + path + ": " + e.getMessage());
            return;
        }
        reply(player, true, "Deleted " + describe(path) + ".");
        broadcast(player.level().getServer());
    }

    private static void rebuildAnimations() throws IOException {
        ANIMATIONS.clear();
        for (String path : FILES.keySet()) {
            if (path.startsWith("animations/")) {
                index(path, Files.readAllBytes(dir.resolve(path)));
            }
        }
    }

    // ---- checks -------------------------------------------------------------------------------------------

    private static @Nullable String checkGeometry(byte[] data) {
        try {
            JsonElement json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (json.isJsonObject() && json.getAsJsonObject().has("minecraft:geometry")
                    && json.getAsJsonObject().get("minecraft:geometry").isJsonArray()
                    && !json.getAsJsonObject().getAsJsonArray("minecraft:geometry").isEmpty()) {
                return null;
            }
            return "That isn't a Bedrock entity model. In Blockbench use File → Export → Export Bedrock Geometry.";
        } catch (Exception e) {
            return "The model file isn't valid JSON (" + e.getMessage() + ").";
        }
    }

    private static @Nullable String checkAnimations(byte[] data) {
        try {
            JsonElement json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (json.isJsonObject() && !AnimationClip.readFile(json.getAsJsonObject()).isEmpty()) {
                return null;
            }
            return "No animations found. In Blockbench use Animation → Export Animations (Bedrock).";
        } catch (Exception e) {
            return "The animation file isn't valid JSON (" + e.getMessage() + ").";
        }
    }

    /** What kind of file a manifest path is: sound, particle, model, texture, animation, or null. */
    public static @Nullable String kindOf(String path) {
        if (path.startsWith("sounds/") && path.endsWith(".ogg")) {
            return "sound";
        } else if (path.startsWith("particles/") && path.endsWith(".png")) {
            return "particle";
        } else if (path.startsWith("models/") && path.endsWith(".geo.json")) {
            return "model";
        } else if (path.startsWith("models/") && path.endsWith(".png")) {
            return "texture";
        } else if (path.startsWith("animations/") && path.endsWith(".animation.json")) {
            return "animation";
        }
        return null;
    }

    /** "sounds/bell.ogg" → "bell". */
    public static String baseName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String describe(String path) {
        String kind = kindOf(path);
        return (kind == null ? "file" : kind) + " '" + baseName(path) + "'";
    }

    /** "Big Bell (1).ogg" → "big_bell_1". */
    private static String slug(String fileName, String suffix) {
        String base = fileName.substring(0, fileName.length() - suffix.length());
        String s = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "file" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static boolean startsWith(byte[] data, String magic) {
        if (data.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (data[i] != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(ServerPlayer player, boolean ok, String message) {
        NpcNetworking.sendToPlayer(player, new EditorResultPayload(ok, message));
    }
}
