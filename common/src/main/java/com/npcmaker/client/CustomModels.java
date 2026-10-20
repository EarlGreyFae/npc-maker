package com.npcmaker.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.npcmaker.Constants;
import com.npcmaker.custom.AnimationClip;
import com.npcmaker.custom.CustomContent;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.network.CustomPayloads;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The client's custom models and animations, read from the downloaded custom content. Models are built (and get
 * their renderer) the first time an NPC wearing them is drawn, and rebuilt after the content changes.
 */
public final class CustomModels {

    /** Set by NpcRenderer when renderers are made, so custom model renderers can be made later. */
    static EntityRendererProvider.@Nullable Context context;

    private static final Map<String, String> GEOMETRY_SHA = new HashMap<>();
    private static final Set<String> TEXTURED = new TreeSet<>();
    private static final Map<String, AnimationClip> ANIMATIONS = new HashMap<>();
    private static final Map<String, CustomModelRenderer> RENDERERS = new HashMap<>();
    private static final Set<String> BROKEN = new TreeSet<>();

    private CustomModels() {
    }

    /** Re-reads models and animations after the server's custom content changed. */
    public static void reload(CustomPayloads.Manifest manifest) {
        GEOMETRY_SHA.clear();
        TEXTURED.clear();
        ANIMATIONS.clear();
        RENDERERS.clear();
        BROKEN.clear();
        for (CustomPayloads.FileEntry f : manifest.files()) {
            String kind = CustomContent.kindOf(f.path());
            String name = CustomContent.baseName(f.path());
            if ("model".equals(kind)) {
                GEOMETRY_SHA.put(name, f.sha1());
            } else if ("texture".equals(kind)) {
                TEXTURED.add(name);
            } else if ("animation".equals(kind)) {
                try {
                    JsonElement json = JsonParser.parseString(ClientCustom.readText(f.sha1()));
                    if (json.isJsonObject()) {
                        for (AnimationClip clip : AnimationClip.readFile(json.getAsJsonObject())) {
                            ANIMATIONS.put(clip.name(), clip);
                        }
                    }
                } catch (Exception e) {
                    Constants.LOG.warn("Custom animation {} couldn't be read: {}", f.path(), e.getMessage());
                }
            }
        }
    }

    /** Custom model names (with a texture), for the "Looks like" list. */
    public static List<String> modelNames() {
        return GEOMETRY_SHA.keySet().stream().filter(TEXTURED::contains).sorted().toList();
    }

    /** Custom animation names, for animation pickers. */
    public static List<String> animationNames() {
        return ANIMATIONS.keySet().stream().sorted().toList();
    }

    public static @Nullable AnimationClip clip(String name) {
        return ANIMATIONS.get(name);
    }

    /** An animation by id ("custom:animation.baker.wave"), or null. */
    public static @Nullable AnimationClip animation(String id) {
        return id.startsWith(CustomContent.ANIMATION_PREFIX) ? ANIMATIONS.get(id.substring(CustomContent.ANIMATION_PREFIX.length())) : null;
    }

    public static float lengthTicks(String id) {
        AnimationClip clip = animation(id);
        return clip == null ? 0 : (float) (clip.length() * 20);
    }

    /**
     * The render state for an NPC wearing a custom model ("npcmaker_custom:baker"), or null to draw it normally
     * (not a custom model, or the model isn't downloaded or couldn't be built).
     */
    public static @Nullable EntityRenderState extract(NpcEntity npc, float partialTicks) {
        CustomModelRenderer renderer = rendererFor(npc);
        return renderer == null ? null : renderer.createRenderState(npc, partialTicks);
    }

    /** The renderer for an NPC wearing a custom model, or null (not one, or not downloaded / buildable). */
    public static @Nullable CustomModelRenderer rendererFor(NpcEntity npc) {
        String id = npc.modelId();
        String prefix = CustomContent.NAMESPACE + ":";
        return id.startsWith(prefix) ? renderer(id.substring(prefix.length())) : null;
    }

    private static @Nullable CustomModelRenderer renderer(String name) {
        CustomModelRenderer made = RENDERERS.get(name);
        if (made != null || BROKEN.contains(name) || context == null) {
            return made;
        }
        String sha = GEOMETRY_SHA.get(name);
        if (sha == null || !TEXTURED.contains(name)) {
            return null;
        }
        try {
            JsonElement json = JsonParser.parseString(ClientCustom.readText(sha));
            BedrockModel model = json.isJsonObject() ? BedrockModel.build(json.getAsJsonObject(), ANIMATIONS, name) : null;
            if (model == null) {
                BROKEN.add(name);
                return null;
            }
            made = new CustomModelRenderer(context, model, name);
            RENDERERS.put(name, made);
            return made;
        } catch (Exception e) {
            Constants.LOG.warn("Custom model '{}' couldn't be built: {}", name, e.getMessage());
            BROKEN.add(name);
            return null;
        }
    }
}
