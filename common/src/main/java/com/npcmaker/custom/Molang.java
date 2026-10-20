package com.npcmaker.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small Molang evaluator for Bedrock animation values: numbers, + - * /, parentheses, comparisons and ?:, the
 * queries animations use most ({@code query.anim_time}, {@code query.life_time}, {@code query.ground_speed},
 * {@code query.modified_move_speed}; {@code q.} works too) and {@code math.sin/cos/abs/min/max/clamp/lerp/pi/sqrt/
 * floor/ceil/round/mod}. Trig takes degrees, like Bedrock. Anything unknown counts as 0, so a fancy expression never
 * breaks a model; it just doesn't move.
 */
public final class Molang {

    /** What an expression can ask about. */
    public record Context(double animTime, double lifeTime, double groundSpeed) {
    }

    public interface Expr {
        double eval(Context ctx);
    }

    private static final Expr ZERO = ctx -> 0;

    private Molang() {
    }

    /** Parses an expression; broken ones become 0. */
    public static Expr parse(String source) {
        try {
            Parser p = new Parser(source.toLowerCase(Locale.ROOT));
            Expr e = p.ternary();
            return e;
        } catch (RuntimeException e) {
            return ZERO;
        }
    }

    public static Expr constant(double value) {
        return ctx -> value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            // Statements like "v.x = 1; return v.x;" aren't supported: use the last part after "return", if any.
            int ret = s.lastIndexOf("return");
            String body = ret >= 0 ? s.substring(ret + 6) : s;
            this.s = body.replace(";", "").trim();
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean eat(String token) {
            skip();
            if (s.startsWith(token, i)) {
                i += token.length();
                return true;
            }
            return false;
        }

        Expr ternary() {
            Expr cond = compare();
            if (eat("?")) {
                Expr a = ternary();
                Expr b = eat(":") ? ternary() : ZERO;
                return ctx -> cond.eval(ctx) != 0 ? a.eval(ctx) : b.eval(ctx);
            }
            return cond;
        }

        Expr compare() {
            Expr left = sum();
            while (true) {
                if (eat("<=")) {
                    Expr l = left, r = sum();
                    left = ctx -> l.eval(ctx) <= r.eval(ctx) ? 1 : 0;
                } else if (eat(">=")) {
                    Expr l = left, r = sum();
                    left = ctx -> l.eval(ctx) >= r.eval(ctx) ? 1 : 0;
                } else if (eat("==")) {
                    Expr l = left, r = sum();
                    left = ctx -> l.eval(ctx) == r.eval(ctx) ? 1 : 0;
                } else if (eat("!=")) {
                    Expr l = left, r = sum();
                    left = ctx -> l.eval(ctx) != r.eval(ctx) ? 1 : 0;
                } else if (eat("<")) {
                    Expr l = left, r = sum();
                    left = ctx -> l.eval(ctx) < r.eval(ctx) ? 1 : 0;
                } else if (eat(">")) {
                    Expr l = left, r = sum();
                    left = ctx -> l.eval(ctx) > r.eval(ctx) ? 1 : 0;
                } else {
                    return left;
                }
            }
        }

        Expr sum() {
            Expr left = product();
            while (true) {
                if (eat("+")) {
                    Expr l = left, r = product();
                    left = ctx -> l.eval(ctx) + r.eval(ctx);
                } else if (eat("-")) {
                    Expr l = left, r = product();
                    left = ctx -> l.eval(ctx) - r.eval(ctx);
                } else {
                    return left;
                }
            }
        }

        Expr product() {
            Expr left = unary();
            while (true) {
                if (eat("*")) {
                    Expr l = left, r = unary();
                    left = ctx -> l.eval(ctx) * r.eval(ctx);
                } else if (eat("/")) {
                    Expr l = left, r = unary();
                    left = ctx -> {
                        double d = r.eval(ctx);
                        return d == 0 ? 0 : l.eval(ctx) / d;
                    };
                } else {
                    return left;
                }
            }
        }

        Expr unary() {
            if (eat("-")) {
                Expr e = unary();
                return ctx -> -e.eval(ctx);
            }
            if (eat("!")) {
                Expr e = unary();
                return ctx -> e.eval(ctx) == 0 ? 1 : 0;
            }
            return atom();
        }

        Expr atom() {
            skip();
            if (eat("(")) {
                Expr e = ternary();
                eat(")");
                return e;
            }
            int start = i;
            if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                double v = Double.parseDouble(s.substring(start, i));
                if (i < s.length() && s.charAt(i) == 'f') {
                    i++;
                }
                return constant(v);
            }
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                i++;
            }
            String name = s.substring(start, i);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("unexpected '" + (i < s.length() ? s.charAt(i) : '?') + "'");
            }
            List<Expr> args = new ArrayList<>();
            if (eat("(")) {
                if (!eat(")")) {
                    do {
                        args.add(ternary());
                    } while (eat(","));
                    eat(")");
                }
            }
            return named(name.replace("q.", "query.").replace("m.", "math."), args);
        }

        private static Expr arg(List<Expr> args, int n) {
            return n < args.size() ? args.get(n) : ZERO;
        }

        private static Expr named(String name, List<Expr> args) {
            Expr a = arg(args, 0);
            Expr b = arg(args, 1);
            Expr c = arg(args, 2);
            return switch (name) {
                case "query.anim_time" -> Context::animTime;
                case "query.life_time" -> Context::lifeTime;
                case "query.ground_speed", "query.modified_move_speed" -> Context::groundSpeed;
                case "query.modified_distance_moved" -> ctx -> ctx.lifeTime() * ctx.groundSpeed();
                case "math.pi" -> constant(Math.PI);
                case "math.sin" -> ctx -> Math.sin(Math.toRadians(a.eval(ctx)));
                case "math.cos" -> ctx -> Math.cos(Math.toRadians(a.eval(ctx)));
                case "math.abs" -> ctx -> Math.abs(a.eval(ctx));
                case "math.sqrt" -> ctx -> Math.sqrt(Math.max(0, a.eval(ctx)));
                case "math.floor" -> ctx -> Math.floor(a.eval(ctx));
                case "math.ceil" -> ctx -> Math.ceil(a.eval(ctx));
                case "math.round" -> ctx -> Math.round(a.eval(ctx));
                case "math.min" -> ctx -> Math.min(a.eval(ctx), b.eval(ctx));
                case "math.max" -> ctx -> Math.max(a.eval(ctx), b.eval(ctx));
                case "math.mod" -> ctx -> {
                    double d = b.eval(ctx);
                    return d == 0 ? 0 : a.eval(ctx) % d;
                };
                case "math.clamp" -> ctx -> Math.max(b.eval(ctx), Math.min(c.eval(ctx), a.eval(ctx)));
                case "math.lerp" -> ctx -> a.eval(ctx) + (b.eval(ctx) - a.eval(ctx)) * c.eval(ctx);
                default -> ZERO;
            };
        }
    }
}
