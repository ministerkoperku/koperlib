package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockZachowanie;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

// bedrock selector options java never had: family=x / family=!x and has_property={...}. used to be cut out of
// the selector, which quietly widened it: mowzie's ice ball summons a frozen on every mob not of family despawn,
// the frozen ones are family despawn, without the filter they summoned themselves, 1000 of them a second
@Mixin(EntitySelectorOptions.class)
public abstract class BedrockSelectorMixin {

    @Shadow
    private static void register(String name, EntitySelectorOptions.Modifier modifier, Predicate<EntitySelectorParser> predicate, Component description) {
        throw new AssertionError();
    }

    @Inject(method = "bootStrap", at = @At("TAIL"))
    private static void koperlib$bedrockOptions(CallbackInfo ci) {
        register("family", parser -> {
            boolean inv = parser.shouldInvertValue();
            String f = parser.getReader().readUnquotedString();
            parser.addPredicate(e -> BedrockZachowanie.families(e).contains(f) != inv);
        }, p -> true, Component.literal("bedrock: has this type family"));
        // has_property={ns:p=value, !ns:p, property=ns:p, ns:n=1..3}
        register("has_property", parser -> {
            StringReader r = parser.getReader();
            r.expect('{');
            r.skipWhitespace();
            while (r.canRead() && r.peek() != '}') {
                boolean negated = false;
                if (r.peek() == '!') { r.skip(); negated = true; }
                String name = word(r);
                r.skipWhitespace();
                if (r.canRead() && r.peek() == '=') {
                    r.skip();
                    r.skipWhitespace();
                    boolean notValue = false;
                    if (r.canRead() && r.peek() == '!') { r.skip(); notValue = true; }
                    String want = r.canRead() && r.peek() == '"' ? r.readQuotedString() : word(r);
                    final boolean neg = notValue;
                    if (name.equals("property")) {
                        // property=ns:p: has it at all
                        parser.addPredicate(e -> (BedrockZachowanie.property(e, want) != null) != neg);
                    } else {
                        parser.addPredicate(e -> matches(BedrockZachowanie.property(e, name), want) != neg);
                    }
                } else {
                    final boolean neg = negated;
                    parser.addPredicate(e -> (BedrockZachowanie.property(e, name) != null) != neg);
                }
                r.skipWhitespace();
                if (r.canRead() && r.peek() == ',') { r.skip(); r.skipWhitespace(); }
            }
            r.expect('}');
        }, p -> true, Component.literal("bedrock: entity property values"));
    }

    // property names carry ':' and '.', brigadier's unquoted string stops at ':'
    private static String word(StringReader r) throws CommandSyntaxException {
        int start = r.getCursor();
        while (r.canRead() && r.peek() != '=' && r.peek() != ',' && r.peek() != '}' && !Character.isWhitespace(r.peek())) r.skip();
        if (r.getCursor() == start) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(r, "property name");
        return r.getString().substring(start, r.getCursor());
    }

    private static boolean matches(Object have, String want) {
        if (have == null) return false;
        if (have instanceof Boolean b) return want.equals(String.valueOf(b));
        if (have instanceof Number n) {
            int dots = want.indexOf("..");
            try {
                if (dots >= 0) {
                    double v = n.doubleValue();
                    String lo = want.substring(0, dots), hi = want.substring(dots + 2);
                    return (lo.isEmpty() || v >= Double.parseDouble(lo)) && (hi.isEmpty() || v <= Double.parseDouble(hi));
                }
                return Math.abs(n.doubleValue() - Double.parseDouble(want)) < 1e-6;
            } catch (NumberFormatException bad) {
                return false;
            }
        }
        return have.toString().equals(want);
    }
}
