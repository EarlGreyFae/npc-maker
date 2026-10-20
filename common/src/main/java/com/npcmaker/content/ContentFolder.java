package com.npcmaker.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.npcmaker.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One folder of JSON definitions keyed by file name, e.g. {@code npcmaker/npcs/}. Files that fail to parse are
 * logged and skipped so one typo doesn't take down every NPC on the server.
 */
public final class ContentFolder<T> {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_\\-]+");

    private final String kind;
    private final Codec<T> codec;
    private final Map<String, T> entries = new LinkedHashMap<>();
    private final UnaryOperator<JsonElement> preprocess;
    private Path dir;
    private DynamicOps<JsonElement> ops = JsonOps.INSTANCE;

    public ContentFolder(String kind, Codec<T> codec) {
        this(kind, codec, UnaryOperator.identity());
    }

    /** @param preprocess applied to each file's JSON before it's read (e.g. merging in an AI template) */
    public ContentFolder(String kind, Codec<T> codec, UnaryOperator<JsonElement> preprocess) {
        this.kind = kind;
        this.codec = codec;
        this.preprocess = preprocess;
    }

    public static boolean isValidId(String id) {
        return VALID_ID.matcher(id).matches();
    }

    /**
     * @param ops JSON ops with registry access, so data-driven parts of item stacks (enchantments etc.) resolve
     */
    public void load(Path dir, DynamicOps<JsonElement> ops) {
        this.dir = dir;
        this.ops = ops;
        entries.clear();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Constants.LOG.error("Could not create {} folder {}", kind, dir, e);
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(this::loadFile);
        } catch (IOException e) {
            Constants.LOG.error("Could not list {} folder {}", kind, dir, e);
        }
        Constants.LOG.info("Loaded {} {} definition(s)", entries.size(), kind);
    }

    private void loadFile(Path file) {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - ".json".length());
        if (!isValidId(id)) {
            Constants.LOG.warn("Skipping {} '{}': file names may only use a-z, 0-9, _ and -", kind, name);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = preprocess.apply(JsonParser.parseReader(reader));
            codec.parse(ops, json)
                    .resultOrPartial(err -> Constants.LOG.error("Invalid {} '{}': {}", kind, name, err))
                    .ifPresent(value -> entries.put(id, value));
        } catch (Exception e) {
            Constants.LOG.error("Could not read {} '{}'", kind, name, e);
        }
    }

    public void save(String id, T value) throws IOException {
        if (dir == null) {
            throw new IOException("Content not loaded yet");
        }
        if (!isValidId(id)) {
            throw new IOException("Invalid id '" + id + "'");
        }
        JsonElement json = codec.encodeStart(ops, value).getOrThrow(IOException::new);
        Files.createDirectories(dir);
        try (Writer writer = Files.newBufferedWriter(dir.resolve(id + ".json"), StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        entries.put(id, value);
    }

    /**
     * Writes hand-shaped JSON as-is (e.g. just a name and an {@code ai_template}, so the file stays short and
     * template changes keep flowing through), then reads it back. Fails if it doesn't read back cleanly.
     */
    public T saveRaw(String id, JsonElement raw) throws IOException {
        if (dir == null) {
            throw new IOException("Content not loaded yet");
        }
        if (!isValidId(id)) {
            throw new IOException("Invalid id '" + id + "'");
        }
        T value = codec.parse(ops, preprocess.apply(raw)).getOrThrow(IOException::new);
        Files.createDirectories(dir);
        try (Writer writer = Files.newBufferedWriter(dir.resolve(id + ".json"), StandardCharsets.UTF_8)) {
            GSON.toJson(raw, writer);
        }
        entries.put(id, value);
        return value;
    }

    /** The file exactly as written (before any template merging), or empty if there's no such file. */
    public Optional<JsonElement> readRaw(String id) {
        if (dir == null || !isValidId(id)) {
            return Optional.empty();
        }
        Path file = dir.resolve(id + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.of(JsonParser.parseReader(reader));
        } catch (Exception e) {
            Constants.LOG.error("Could not read {} '{}'", kind, id, e);
            return Optional.empty();
        }
    }

    /** The full JSON for a loaded value (template settings merged in), e.g. for the editor to show. */
    public Optional<JsonElement> encode(T value) {
        return codec.encodeStart(ops, value).result();
    }

    public boolean exists(String id) {
        return entries.containsKey(id) || (dir != null && isValidId(id) && Files.exists(dir.resolve(id + ".json")));
    }

    public Optional<T> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Map<String, T> all() {
        return Collections.unmodifiableMap(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
