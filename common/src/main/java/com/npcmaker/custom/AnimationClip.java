package com.npcmaker.custom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One animation from a Bedrock {@code .animation.json} file (as Blockbench exports them): per bone, rotation /
 * position / scale keyframes over time, with linear or smooth (catmull-rom) easing, step keyframes ({@code pre} /
 * {@code post}), and Molang values. Bone names are matched without case or underscores, so {@code rightArm} and
 * {@code right_arm} are the same bone.
 *
 * @param length seconds (the file's animation_length, or the last keyframe)
 */
public record AnimationClip(String name, double length, Loop loop, Map<String, Bone> bones) {

    public enum Loop { ONCE, LOOP, HOLD }

    /** The three channels of one bone; any may be null. */
    public record Bone(Channel rotation, Channel position, Channel scale) {
    }

    /** Keyframes sorted by time; a constant value is one keyframe at 0. */
    public record Channel(List<Key> keys) {

        /** The value at {@code time} seconds. */
        public double[] sample(double time, Molang.Context ctx) {
            if (keys.size() == 1 || time <= keys.get(0).time()) {
                return keys.get(0).pre().eval(ctx);
            }
            Key last = keys.get(keys.size() - 1);
            if (time >= last.time()) {
                return last.post().eval(ctx);
            }
            int i = 0;
            while (i < keys.size() - 2 && keys.get(i + 1).time() <= time) {
                i++;
            }
            Key a = keys.get(i);
            Key b = keys.get(i + 1);
            double t = (time - a.time()) / Math.max(1e-6, b.time() - a.time());
            double[] va = a.post().eval(ctx);
            double[] vb = b.pre().eval(ctx);
            if (a.smooth() || b.smooth()) {
                double[] v0 = (i > 0 ? keys.get(i - 1) : a).post().eval(ctx);
                double[] v3 = (i + 2 < keys.size() ? keys.get(i + 2) : b).pre().eval(ctx);
                double[] out = new double[3];
                for (int k = 0; k < 3; k++) {
                    out[k] = catmullRom(t, v0[k], va[k], vb[k], v3[k]);
                }
                return out;
            }
            return new double[]{va[0] + (vb[0] - va[0]) * t, va[1] + (vb[1] - va[1]) * t, va[2] + (vb[2] - va[2]) * t};
        }

        private static double catmullRom(double t, double p0, double p1, double p2, double p3) {
            return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
        }
    }

    /** A keyframe: the value arriving ({@code pre}) and leaving ({@code post}); usually the same. */
    public record Key(double time, Vec pre, Vec post, boolean smooth) {
    }

    /** Three Molang expressions. */
    public record Vec(Molang.Expr x, Molang.Expr y, Molang.Expr z) {
        double[] eval(Molang.Context ctx) {
            return new double[]{x.eval(ctx), y.eval(ctx), z.eval(ctx)};
        }
    }

    /** The bone's channels, matched loosely by name, or null. */
    public Bone bone(String boneName) {
        return bones.get(key(boneName));
    }

    /** Bone names compared without case, underscores or dots. */
    public static String key(String boneName) {
        return boneName.toLowerCase(Locale.ROOT).replace("_", "").replace(".", "");
    }

    /** The time to sample at, given seconds since the animation started. */
    public double timeAt(double seconds) {
        if (length <= 0) {
            return 0;
        }
        return switch (loop) {
            case LOOP -> seconds % length;
            case HOLD, ONCE -> Math.min(seconds, length);
        };
    }

    // ---- reading ------------------------------------------------------------------------------------------

    /** Every animation in a Bedrock animation file. */
    public static List<AnimationClip> readFile(JsonObject root) {
        List<AnimationClip> clips = new ArrayList<>();
        if (!root.has("animations") || !root.get("animations").isJsonObject()) {
            return clips;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("animations").entrySet()) {
            if (e.getValue().isJsonObject()) {
                clips.add(read(e.getKey(), e.getValue().getAsJsonObject()));
            }
        }
        return clips;
    }

    private static AnimationClip read(String name, JsonObject json) {
        Loop loop = Loop.ONCE;
        JsonElement l = json.get("loop");
        if (l != null && l.isJsonPrimitive()) {
            if (l.getAsJsonPrimitive().isBoolean()) {
                loop = l.getAsBoolean() ? Loop.LOOP : Loop.ONCE;
            } else if ("hold_on_last_frame".equals(l.getAsString())) {
                loop = Loop.HOLD;
            }
        }
        Map<String, Bone> bones = new LinkedHashMap<>();
        double maxTime = 0;
        if (json.has("bones") && json.get("bones").isJsonObject()) {
            for (Map.Entry<String, JsonElement> b : json.getAsJsonObject("bones").entrySet()) {
                if (!b.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject bone = b.getValue().getAsJsonObject();
                Channel rot = channel(bone.get("rotation"));
                Channel pos = channel(bone.get("position"));
                Channel scale = channel(bone.get("scale"));
                for (Channel c : new Channel[]{rot, pos, scale}) {
                    if (c != null) {
                        maxTime = Math.max(maxTime, c.keys().get(c.keys().size() - 1).time());
                    }
                }
                bones.put(key(b.getKey()), new Bone(rot, pos, scale));
            }
        }
        double length = json.has("animation_length") && json.get("animation_length").isJsonPrimitive()
                ? json.get("animation_length").getAsDouble() : maxTime;
        return new AnimationClip(name, length, loop, bones);
    }

    private static Channel channel(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        List<Key> keys = new ArrayList<>();
        if (e.isJsonObject() && !looksLikeKey(e.getAsJsonObject())) {
            TreeMap<Double, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> k : e.getAsJsonObject().entrySet()) {
                try {
                    sorted.put(Double.parseDouble(k.getKey()), k.getValue());
                } catch (NumberFormatException ignored) {
                    // not a time
                }
            }
            for (Map.Entry<Double, JsonElement> k : sorted.entrySet()) {
                keys.add(key(k.getKey(), k.getValue()));
            }
        } else {
            keys.add(key(0, e));
        }
        return keys.isEmpty() ? null : new Channel(keys);
    }

    private static boolean looksLikeKey(JsonObject o) {
        return o.has("pre") || o.has("post") || o.has("lerp_mode");
    }

    private static Key key(double time, JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            Vec post = vec(o.has("post") ? o.get("post") : o.get("pre"));
            Vec pre = o.has("pre") ? vec(o.get("pre")) : post;
            boolean smooth = o.has("lerp_mode") && "catmullrom".equals(o.get("lerp_mode").getAsString());
            return new Key(time, pre, post, smooth);
        }
        Vec v = vec(e);
        return new Key(time, v, v, false);
    }

    private static Vec vec(JsonElement e) {
        if (e == null) {
            Molang.Expr zero = Molang.constant(0);
            return new Vec(zero, zero, zero);
        }
        if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
            return new Vec(expr(e.getAsJsonArray().get(0)), expr(e.getAsJsonArray().get(1)), expr(e.getAsJsonArray().get(2)));
        }
        if (e.isJsonArray() && e.getAsJsonArray().size() == 1) {
            Molang.Expr one = expr(e.getAsJsonArray().get(0));
            return new Vec(one, one, one);
        }
        Molang.Expr one = expr(e);
        return new Vec(one, one, one);
    }

    private static Molang.Expr expr(JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return Molang.constant(e.getAsDouble());
        }
        return e.isJsonPrimitive() ? Molang.parse(e.getAsString()) : Molang.constant(0);
    }
}
