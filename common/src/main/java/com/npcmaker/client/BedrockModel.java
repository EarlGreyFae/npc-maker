package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.npcmaker.custom.AnimationClip;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench model (Bedrock entity geometry) turned into Minecraft model parts, plus its animations. Bedrock's
 * coordinates have y up from the feet; Minecraft models have y down from 24 px above the feet, so a bone pivot
 * (x, y, z) sits at (x, 24 − y, z), and a cube from origin o with size s is the box (o.x − p.x, p.y − o.y − s.y,
 * o.z − p.z) relative to its bone's pivot p. Rotations keep their signs (degrees → radians). This reproduces vanilla's
 * own player model exactly from the Bedrock player geometry, so models line up with vanilla's.
 *
 * <p>Box UV only: cubes with per-face UV use their north face's corner. Animations: whatever is playing (an emote or
 * custom idle pose), else the model's own "walk"/"move" animation while it walks, else its "idle" animation.
 */
public class BedrockModel extends EntityModel<CustomModelState> {

    private final Map<String, ModelPart> bones;
    private final @Nullable ModelPart head;
    private final @Nullable AnimationClip idle;
    private final @Nullable AnimationClip walk;

    private BedrockModel(ModelPart root, Map<String, ModelPart> bones, @Nullable AnimationClip idle, @Nullable AnimationClip walk) {
        super(root);
        this.bones = bones;
        this.head = bones.get(AnimationClip.key("head"));
        this.idle = idle;
        this.walk = walk;
    }

    /** Builds the model from a .geo.json file (its first geometry); null if it has none. */
    public static @Nullable BedrockModel build(JsonObject file, Map<String, AnimationClip> animations, String modelName) {
        if (!file.has("minecraft:geometry") || !file.get("minecraft:geometry").isJsonArray()
                || file.getAsJsonArray("minecraft:geometry").isEmpty()) {
            return null;
        }
        JsonObject geometry = file.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject description = geometry.has("description") ? geometry.getAsJsonObject("description") : new JsonObject();
        int texW = intOf(description, "texture_width", intOf(geometry, "texturewidth", 64));
        int texH = intOf(description, "texture_height", intOf(geometry, "textureheight", 64));

        Map<String, JsonObject> byName = new LinkedHashMap<>();
        if (geometry.has("bones") && geometry.get("bones").isJsonArray()) {
            for (JsonElement b : geometry.getAsJsonArray("bones")) {
                if (b.isJsonObject() && b.getAsJsonObject().has("name")) {
                    byName.put(b.getAsJsonObject().get("name").getAsString(), b.getAsJsonObject());
                }
            }
        }
        MeshDefinition mesh = new MeshDefinition();
        Map<String, List<String>> paths = new HashMap<>();
        for (String name : byName.keySet()) {
            addBone(mesh.getRoot(), name, byName, paths, new ArrayList<>());
        }
        ModelPart root = LayerDefinition.create(mesh, texW, texH).bakeRoot();
        Map<String, ModelPart> parts = new HashMap<>();
        for (Map.Entry<String, List<String>> e : paths.entrySet()) {
            ModelPart part = root;
            for (String step : e.getValue()) {
                part = part.getChild(step);
            }
            parts.put(AnimationClip.key(e.getKey()), part);
        }
        return new BedrockModel(root, parts, findClip(animations, modelName, "idle"), findClip(animations, modelName, "walk", "move"));
    }

    /** The model's own animation whose name ends in one of the words, preferring ones mentioning the model. */
    private static @Nullable AnimationClip findClip(Map<String, AnimationClip> animations, String modelName, String... words) {
        AnimationClip any = null;
        for (AnimationClip clip : animations.values()) {
            String n = clip.name().toLowerCase(java.util.Locale.ROOT);
            for (String word : words) {
                if (n.endsWith("." + word) || n.equals(word)) {
                    if (n.contains(modelName)) {
                        return clip;
                    }
                    if (any == null) {
                        any = clip;
                    }
                }
            }
        }
        return any;
    }

    /** Adds a bone under its parent (adding the parent first), remembering the path to it. */
    private static PartDefinition addBone(PartDefinition meshRoot, String name, Map<String, JsonObject> bones,
                                          Map<String, List<String>> paths, List<String> visiting) {
        if (paths.containsKey(name)) {
            return find(meshRoot, paths.get(name));
        }
        JsonObject bone = bones.get(name);
        String parentName = bone.has("parent") ? bone.get("parent").getAsString() : null;
        PartDefinition parent = meshRoot;
        float[] parentPivot = {0, 0, 0};
        boolean hasParent = parentName != null && bones.containsKey(parentName) && !visiting.contains(parentName);
        if (hasParent) {
            visiting.add(name);
            parent = addBone(meshRoot, parentName, bones, paths, visiting);
            parentPivot = vec(bones.get(parentName), "pivot");
        }
        float[] pivot = vec(bone, "pivot");
        float[] rot = vec(bone, "rotation");
        PartPose pose = hasParent
                ? PartPose.offsetAndRotation(pivot[0] - parentPivot[0], parentPivot[1] - pivot[1], pivot[2] - parentPivot[2],
                rad(rot[0]), rad(rot[1]), rad(rot[2]))
                : PartPose.offsetAndRotation(pivot[0], 24 - pivot[1], pivot[2], rad(rot[0]), rad(rot[1]), rad(rot[2]));
        boolean boneMirror = bone.has("mirror") && bone.get("mirror").getAsBoolean();
        float boneInflate = bone.has("inflate") ? bone.get("inflate").getAsFloat() : 0;

        CubeListBuilder cubes = CubeListBuilder.create();
        List<JsonObject> rotatedCubes = new ArrayList<>();
        if (bone.has("cubes") && bone.get("cubes").isJsonArray()) {
            for (JsonElement c : bone.getAsJsonArray("cubes")) {
                if (!c.isJsonObject()) {
                    continue;
                }
                JsonObject cube = c.getAsJsonObject();
                if (cube.has("rotation")) {
                    rotatedCubes.add(cube);
                } else {
                    addCube(cubes, cube, pivot, boneMirror, boneInflate);
                }
            }
        }
        PartDefinition part = parent.addOrReplaceChild(name, cubes, pose);
        // Cubes with their own rotation get a little part of their own, turning around the cube's pivot.
        for (int i = 0; i < rotatedCubes.size(); i++) {
            JsonObject cube = rotatedCubes.get(i);
            float[] cubePivot = cube.has("pivot") ? vec(cube, "pivot") : pivot;
            float[] cubeRot = vec(cube, "rotation");
            CubeListBuilder single = CubeListBuilder.create();
            addCube(single, cube, cubePivot, boneMirror, boneInflate);
            part.addOrReplaceChild(name + "_r" + i, single, PartPose.offsetAndRotation(cubePivot[0] - pivot[0],
                    pivot[1] - cubePivot[1], cubePivot[2] - pivot[2], rad(cubeRot[0]), rad(cubeRot[1]), rad(cubeRot[2])));
        }
        List<String> path = new ArrayList<>(hasParent ? paths.get(parentName) : List.of());
        path.add(name);
        paths.put(name, path);
        return part;
    }

    private static PartDefinition find(PartDefinition root, List<String> path) {
        PartDefinition part = root;
        for (String step : path) {
            part = part.getChild(step);
        }
        return part;
    }

    private static void addCube(CubeListBuilder builder, JsonObject cube, float[] pivot, boolean boneMirror, float boneInflate) {
        float[] origin = vec(cube, "origin");
        float[] size = vec(cube, "size");
        float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : boneInflate;
        boolean mirror = cube.has("mirror") ? cube.get("mirror").getAsBoolean() : boneMirror;
        int u = 0;
        int v = 0;
        JsonElement uv = cube.get("uv");
        if (uv != null && uv.isJsonArray() && uv.getAsJsonArray().size() >= 2) {
            u = (int) Math.floor(uv.getAsJsonArray().get(0).getAsFloat());
            v = (int) Math.floor(uv.getAsJsonArray().get(1).getAsFloat());
        } else if (uv != null && uv.isJsonObject()) {
            JsonObject faces = uv.getAsJsonObject();
            JsonObject north = faces.has("north") ? faces.getAsJsonObject("north") : null;
            if (north != null && north.has("uv")) {
                JsonArray nuv = north.getAsJsonArray("uv");
                // per-face UV isn't supported; line the box up so the front face lands where the north face is
                u = (int) Math.floor(nuv.get(0).getAsFloat() - size[2]);
                v = (int) Math.floor(nuv.get(1).getAsFloat() - size[2]);
            }
        }
        builder.texOffs(u, v).mirror(mirror).addBox(origin[0] - pivot[0], pivot[1] - origin[1] - size[1], origin[2] - pivot[2],
                size[0], size[1], size[2], new CubeDeformation(inflate));
    }

    private static float[] vec(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
            JsonArray a = e.getAsJsonArray();
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }
        return new float[]{0, 0, 0};
    }

    private static int intOf(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    // ---- posing -------------------------------------------------------------------------------------------

    @Override
    public void setupAnim(CustomModelState state) {
        super.setupAnim(state);
        resetPose(); // animations add on top of the model's own pose, so start from it every frame
        if (head != null) {
            head.yRot += state.yRot * ((float) Math.PI / 180F);
            head.xRot += state.xRot * ((float) Math.PI / 180F);
        }
        double life = state.ageInTicks / 20.0;
        AnimationClip playing = state.animation != null ? CustomModels.animation(state.animation) : null;
        if (playing != null) {
            float weight = Math.min(1.0F, Math.min(state.animationAge / 4.0F, (state.animationLength - state.animationAge) / 4.0F));
            CustomAnimator.apply(playing, bones, state.animationAge / 20.0, state.walkAnimationSpeed, life, Math.max(0, weight));
            return;
        }
        boolean walking = state.walkAnimationSpeed > 0.05F;
        if (walking && walk != null) {
            CustomAnimator.apply(walk, bones, state.walkAnimationPos / 8.0, state.walkAnimationSpeed, life, Math.min(1.0F, state.walkAnimationSpeed * 2));
            return;
        }
        AnimationClip pose = !walking && state.idlePose != null ? CustomModels.animation(state.idlePose) : null;
        if (pose != null) {
            CustomAnimator.apply(pose, bones, life, 0, life, 1.0F);
        } else if (idle != null) {
            CustomAnimator.apply(idle, bones, life, state.walkAnimationSpeed, life, 1.0F);
        }
    }
}
