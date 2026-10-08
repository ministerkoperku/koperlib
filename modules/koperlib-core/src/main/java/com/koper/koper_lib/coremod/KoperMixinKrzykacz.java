package com.koper.koper_lib.coremod;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// every koperlib mixin that got applied but whose handler NOTHING calls gets yelled at here.
// require = 0 + a renamed/moved/inherited mc method = the mixin just quietly dies, this catches that.
// -Dkoperlib.mixinAudit=true force loads all targets so late classes get checked too (omni turns it on)
public class KoperMixinKrzykacz implements IMixinConfigPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("KoperLib/MixinGuard");
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger DEAD = new AtomicInteger();
    private static final List<String> DEAD_LIST = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final String[] HANDLER_PREFIXES = {"handler$", "redirect$", "modify$", "localvar$", "constant$",
        "args$", "wrapOperation$", "wrapWithCondition$", "modifyExpressionValue$", "modifyReturnValue$", "modifyReceiver$"};

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        APPLIED.add(mixinClassName);
        try {
            check(targetClassName, targetClass, mixinClassName, mixinInfo);
        } catch (Throwable t) {
            LOG.error("[koperlib mixin] could not verify {} on {}", mixinClassName, targetClassName, t);
        }
    }

    private static void check(String targetName, ClassNode target, String mixinName, IMixinInfo info) {
        Set<String> called = new java.util.HashSet<>();
        for (MethodNode m : target.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)) called.add(call.name + call.desc);
                // WrapOperation & co reach their handler through a lambda (invokedynamic), not a plain call
                if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy && indy.bsmArgs != null)
                    for (Object arg : indy.bsmArgs)
                        if (arg instanceof org.objectweb.asm.Handle h && h.getOwner().equals(target.name)) called.add(h.getName() + h.getDesc());
            }
        }
        ClassNode mixinNode = info.getClassNode(0);
        for (MethodNode m : target.methods) {
            // injector handlers carry no @MixinMerged: recognise them by the handler$<id>$<mod>$<name> shape plus
            // a method of that name in this mixin that has an injector annotation
            if (!isHandler(m.name)) continue;
            MethodNode original = originalOf(mixinNode, m.name);
            if (original == null || describe(original).isEmpty()) continue;
            if (called.contains(m.name + m.desc)) continue;
            // MixinExtras (WrapOperation & co) weaves its calls in AFTER postApply, so for those the question
            // is whether the call they wrap still sits in the target method
            if (isMixinExtras(original) && lateTargetPresent(target, original)) continue;
            String what = original == null ? m.name : original.name;
            String declared = original == null ? "" : describe(original);
            String reason = reason(target, original);
            String line = mixinName + "#" + what + " -> " + targetName;
            DEAD_LIST.add(line);
            DEAD.incrementAndGet();
            LOG.error("[koperlib mixin] NIE ZAŁADOWAŁEM SIĘ / DID NOT TAKE EFFECT: {} on {}. {} {}. "
                + "Whatever feature this mixin carries is NOT working.", mixinName + "#" + what, targetName, reason, declared);
        }
    }


    private static boolean isHandler(String name) {
        for (String p : HANDLER_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private static MethodNode originalOf(ClassNode mixin, String mergedName) {
        MethodNode best = null;
        for (MethodNode m : mixin.methods) {
            if (mergedName.endsWith("$" + m.name) && (best == null || m.name.length() > best.name.length())) best = m;
        }
        return best;
    }

    // "@Inject(method=[render], at=INVOKE Lx;y()V)" from the handler's own annotation
    private static String describe(MethodNode m) {
        List<AnnotationNode> all = new ArrayList<>();
        if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
        for (AnnotationNode a : all) {
            if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") && !a.desc.startsWith("Lcom/llamalad7/mixinextras/")) continue;
            String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
            return "Declared as @" + kind + "(" + values(a) + ")";
        }
        return "";
    }

    private static String values(AnnotationNode a) {
        if (a.values == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            Object k = a.values.get(i), v = a.values.get(i + 1);
            if (!"method".equals(k) && !"at".equals(k) && !"target".equals(k) && !"value".equals(k)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append('=').append(show(v));
        }
        return sb.toString();
    }

    private static boolean isMixinExtras(MethodNode m) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
            if (list != null) for (AnnotationNode a : list) if (a.desc.startsWith("Lcom/llamalad7/mixinextras/")) return true;
        return false;
    }

    // every method=... of the annotation that contains every INVOKE/FIELD target of its @At
    private static boolean lateTargetPresent(ClassNode target, MethodNode original) {
        AnnotationNode inj = injector(original);
        if (inj == null) return false;
        List<String> methods = new ArrayList<>(), wanted = new ArrayList<>();
        collect(inj, methods, wanted);
        if (wanted.isEmpty()) return true; // HEAD/RETURN style points always exist when the method does
        for (String t : methods) {
            String name = t.contains("(") ? t.substring(0, t.indexOf('(')) : t;
            String d = t.contains("(") ? t.substring(t.indexOf('(')) : null;
            for (MethodNode m : target.methods) {
                if (!m.name.equals(name) || (d != null && !m.desc.equals(d)) || m.instructions == null) continue;
                for (String w : wanted) {
                    String owner = w.substring(1, w.indexOf(';'));
                    String rest = w.substring(w.indexOf(';') + 1);
                    for (AbstractInsnNode insn : m.instructions) {
                        if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && rest.equals(c.name + c.desc)) return true;
                        if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && f.owner.equals(owner) && rest.equals(f.name + ":" + f.desc)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static AnnotationNode injector(MethodNode m) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
            if (list != null) for (AnnotationNode a : list)
                if (a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || a.desc.startsWith("Lcom/llamalad7/mixinextras/")) return a;
        return null;
    }

    // method names and @At targets straight off the annotation tree
    private static void collect(Object v, List<String> methods, List<String> targets) {
        if (v instanceof AnnotationNode a && a.values != null) {
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                Object k = a.values.get(i), val = a.values.get(i + 1);
                if ("method".equals(k) && val instanceof List<?> l) for (Object o : l) methods.add(String.valueOf(o));
                else if ("target".equals(k) && val instanceof String t && t.startsWith("L")) targets.add(t);
                else collect(val, methods, targets);
            }
        } else if (v instanceof List<?> l) for (Object o : l) collect(o, methods, targets);
    }

    private static String show(Object v) {
        if (v instanceof AnnotationNode n) return "@At(" + values(n) + ")";
        if (v instanceof List<?> list) return list.stream().map(KoperMixinKrzykacz::show).toList().toString();
        return String.valueOf(v);
    }

    private static String reason(ClassNode target, MethodNode original) {
        if (original == null) return "Its injection point matched nothing.";
        String desc = describe(original);
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("method=\\[([^\\]]*)]").matcher(desc);
        if (mm.find()) {
            for (String t : mm.group(1).split(",\\s*")) {
                String name = t.contains("(") ? t.substring(0, t.indexOf('(')) : t;
                String d = t.contains("(") ? t.substring(t.indexOf('(')) : null;
                boolean declared = target.methods.stream().anyMatch(x -> x.name.equals(name) && (d == null || x.desc.equals(d)));
                if (!declared) return "Reason: " + target.name + " does not DECLARE '" + t + "' (renamed, parameters changed, "
                    + "or only inherited from a superclass; a mixin can only hook methods the class itself declares).";
            }
        }
        return "Reason: the method is there, but the injection point inside it (the call/field/constant in @At) "
            + "no longer occurs there (Minecraft moved or changed that code).";
    }

    // called once the game is up: optional audit, then one line saying how bad it is
    public static void summary() {
        if (Boolean.getBoolean("koperlib.mixinAudit")) {
            try { MixinEnvironment.getCurrentEnvironment().audit(); }
            catch (Throwable t) { LOG.error("[koperlib mixin] audit failed", t); }
        }
        if (DEAD.get() == 0) LOG.info("[koperlib mixin] all {} applied koperlib mixins took effect", APPLIED.size());
        else LOG.error("[koperlib mixin] {} koperlib mixin handler(s) DID NOT TAKE EFFECT: {}", DEAD.get(), DEAD_LIST);
    }

    public static int deadCount() { return DEAD.get(); }
    public static List<String> dead() { return List.copyOf(DEAD_LIST); }
}
