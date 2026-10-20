package com.npcmaker.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A conversation, loaded from {@code <world>/npcmaker/dialogues/<id>.json}: named nodes, each with pages of NPC
 * text and the player's replies. A reply with no {@code goto} ends the conversation.
 *
 * <pre>
 * {
 *   "start": "greet",
 *   "nodes": {
 *     "greet": {
 *       "text": ["Halt, traveller.", "The night is full of the restless dead."],
 *       "replies": [
 *         { "text": "Who are you?", "goto": "who" },
 *         { "text": "Farewell." }
 *       ]
 *     }
 *   }
 * }
 * </pre>
 * Text may use {@code {player}} and {@code {npc}}, and styling tags like {@code <gold>…</gold>} (see TextTags).
 * Optional {@code "openers"} pick a different first node based on conditions.
 */
public record DialogueTree(String start, List<Opener> openers, Map<String, Node> nodes) {

    /**
     * An alternative first node: the first opener whose conditions all pass is where the conversation starts;
     * if none match, it starts at {@code start}. E.g. greet returning players differently once a flag is set.
     */
    public record Opener(List<DialogueCondition> conditions, String node) {
        public static final Codec<Opener> CODEC = RecordCodecBuilder.create(i -> i.group(
                DialogueCondition.CODEC.listOf().optionalFieldOf("if", List.of()).forGetter(Opener::conditions),
                Codec.STRING.fieldOf("goto").forGetter(Opener::node)
        ).apply(i, Opener::new));
    }

    public record Node(List<String> pages, List<Reply> replies) {
        private static final Codec<List<String>> PAGES_CODEC =
                Codec.withAlternative(Codec.STRING.listOf(1, 64), Codec.STRING.xmap(List::of, list -> list.get(0)));

        public static final Codec<Node> CODEC = RecordCodecBuilder.create(i -> i.group(
                PAGES_CODEC.fieldOf("text").forGetter(Node::pages),
                Reply.CODEC.listOf(0, 9).optionalFieldOf("replies", List.of()).forGetter(Node::replies)
        ).apply(i, Node::new));
    }

    /**
     * @param conditions all must pass for the reply to be offered
     * @param actions    run in order when the reply is picked
     * @param next       node to go to afterwards; empty ends the conversation
     */
    public record Reply(String text, List<DialogueCondition> conditions, List<DialogueAction> actions, Optional<String> next) {
        public static final Codec<Reply> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("text").forGetter(Reply::text),
                DialogueCondition.CODEC.listOf().optionalFieldOf("if", List.of()).forGetter(Reply::conditions),
                DialogueAction.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(Reply::actions),
                Codec.STRING.optionalFieldOf("goto").forGetter(Reply::next)
        ).apply(i, Reply::new));
    }

    public static final Codec<DialogueTree> CODEC = RecordCodecBuilder.<DialogueTree>create(i -> i.group(
            Codec.STRING.fieldOf("start").forGetter(DialogueTree::start),
            Opener.CODEC.listOf().optionalFieldOf("openers", List.of()).forGetter(DialogueTree::openers),
            Codec.unboundedMap(Codec.STRING, Node.CODEC).fieldOf("nodes").forGetter(DialogueTree::nodes)
    ).apply(i, DialogueTree::new)).validate(DialogueTree::validate);

    /** Catch typos in node names when the file loads instead of mid-conversation. */
    private static DataResult<DialogueTree> validate(DialogueTree tree) {
        List<String> problems = new ArrayList<>();
        if (!tree.nodes.containsKey(tree.start)) {
            problems.add("start node '" + tree.start + "' doesn't exist");
        }
        tree.openers.stream().map(Opener::node).filter(n -> !tree.nodes.containsKey(n))
                .forEach(n -> problems.add("an opener goes to missing node '" + n + "'"));
        tree.nodes.forEach((name, node) -> node.replies().forEach(reply -> reply.next()
                .filter(target -> !tree.nodes.containsKey(target))
                .ifPresent(target -> problems.add("node '" + name + "' has a reply going to missing node '" + target + "'"))));
        return problems.isEmpty() ? DataResult.success(tree) : DataResult.error(() -> String.join("; ", problems));
    }

    /** NPCs with only greeting lines get a one-node conversation so they still use the dialogue box. */
    public static DialogueTree fromGreeting(List<String> lines) {
        return new DialogueTree("greeting", List.of(), Map.of("greeting", new Node(lines, List.of())));
    }
}
