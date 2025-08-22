package jadx.plugins.googlehelpers;

import java.util.Objects;

import jadx.api.plugins.pass.JadxPassInfo;
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo;
import jadx.api.plugins.pass.types.JadxDecompilePass;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.ConstStringNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.InsnWrapArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jadx.core.utils.exceptions.DecodeException;

public class RenameFromLogsPass implements JadxDecompilePass {
    private final GoogleHelpersOptions options;
    private static final Logger LOG = LoggerFactory.getLogger(RenameFromLogsPass.class);

    private MethodRef resolvedFactoryRef;
    private MethodRef resolvedLocationRef;

    public RenameFromLogsPass(GoogleHelpersOptions options) {
        this.options = options;
    }

    @Override
    public JadxPassInfo getInfo() {
        return new OrderedJadxPassInfo(
                "GoogleHelpersRename",
                "Rename class based on Flogger/log location strings")
                .before("RegionMakerVisitor");
    }

    @Override
    public void init(jadx.core.dex.nodes.RootNode root) {
        // Resolve method refs once per decompilation load
        if (!options.getFactoryMethodRef().isEmpty()) {
            resolvedFactoryRef = MethodRef.parse(options.getFactoryMethodRef());
            LOG.info("google-helpers: using configured factoryRef={}", resolvedFactoryRef);
        } else {
            resolvedFactoryRef = discoverGoogleLoggerFactory(root);
            if (resolvedFactoryRef != null) {
                LOG.info("google-helpers: discovered factoryRef={} on load", resolvedFactoryRef);
            } else {
                LOG.info("google-helpers: factoryRef not configured and discovery failed");
            }
        }
        resolvedLocationRef = MethodRef.parse(options.getLocationMethodRef());
    }

    @Override
    public boolean visit(ClassNode cls) {
        String target = options.getTargetClass();
        if (target.isEmpty()) {
            LOG.debug("google-helpers: pass skipped (no targetClass set)");
            return true; // no automatic run without explicit target
        }
        String normTarget = normalizeClassName(target);
        String clsRaw = cls.getRawName();
        String clsFull = cls.getFullName();
        if (!clsRaw.endsWith(normTarget) && !clsFull.equals(normTarget.replace('/', '.'))) {
            return true; // other class
        }
        LOG.info("google-helpers: processing class: {}", cls.getFullName());
        MethodRef factoryRef = resolvedFactoryRef;
        MethodRef locationRef = resolvedLocationRef;
        LOG.info("google-helpers: using factoryRef={}, locationRef={}",
                factoryRef != null ? factoryRef : "<auto-none>",
                locationRef != null ? locationRef : "<not-set>");
        boolean changed = renameClassFromLogs(cls, factoryRef, locationRef);
        if (!changed) {
            LOG.info("google-helpers: no matching logger calls found for {}", cls.getFullName());
        }
        return true; // skip method visits
    }

    @Override
    public void visit(MethodNode mth) {
        // handled in visit(ClassNode)
    }

    public static boolean renameClassFromLogs(ClassNode cls, MethodRef factoryRef, MethodRef locationRef) {
        if (cls == null) return false;
        if (factoryRef == null && locationRef == null) {
            LOG.info("google-helpers: both factoryRef and locationRef are null; nothing to match");
        }
        MethodNode mth = cls.getClassInitMth();
        if (mth == null) {
            LOG.info("google-helpers: <clinit> not found in {}", cls.getFullName());
            return false;
        }
        try {
            LOG.debug("google-helpers: <clinit> initial state: isNoCode={} loaded? (implicit)", mth.isNoCode());
            mth.load();
        } catch (DecodeException e) {
            LOG.warn("google-helpers: failed to load <clinit> for {}: {}", cls.getFullName(), e.getMessage());
            return false;
        }
        InsnNode[] insns = mth.getInstructions();
        if ((insns == null || insns.length == 0) && mth.isNoCode()) {
            // try reload (forces re-decode)
            try {
                LOG.debug("google-helpers: <clinit> has no code after load, trying reload for {}", cls.getFullName());
                mth.reload();
                insns = mth.getInstructions();
            } catch (Exception e) {
                LOG.debug("google-helpers: reload failed for {}: {}", cls.getFullName(), e.toString());
            }
        }
        if (insns == null || insns.length == 0) {
            LOG.debug("google-helpers: <clinit> has no instructions for {}", cls.getFullName());
            return false;
        }
        LOG.debug("google-helpers: scanning <clinit> {} ({} insns)", mth.getMethodInfo().getRawFullId(), insns.length);
        for (int i = 0; i < insns.length; i++) {
            InsnNode insn = insns[i];
            LOG.debug("google-helpers: insn #{} {}", i, insn);
            if (!(insn instanceof InvokeNode)) {
                continue;
            }
            InvokeNode inv = (InvokeNode) insn;
            MethodInfo call = inv.getCallMth();
            LOG.debug("google-helpers: invoke at #{}, call={}", i, call.getRawFullId());
            if (factoryRef != null) {
                boolean match = methodMatches(call, factoryRef);
                LOG.debug("google-helpers: compare with factoryRef {} => {}", refToString(factoryRef), match);
                if (match) {
                    String clsName = extractStringArg(inv, 0, i, insns);
                    if (clsName != null) {
                        LOG.info("google-helpers: found factory call {} in {} -> {}",
                                call.getRawFullId(), mth.getMethodInfo().getRawFullId(), clsName);
                        renameDeclaringClass(mth.getParentClass(), clsName);
                        return true;
                    }
                }
            }
            if (locationRef != null) {
                boolean match = methodMatches(call, locationRef);
                LOG.debug("google-helpers: compare with locationRef {} => {}", refToString(locationRef), match);
                if (match) {
                    String clsName = extractStringArg(inv, 0, i, insns);
                    if (clsName != null) {
                        LOG.info("google-helpers: found location call {} in {} -> {}",
                                call.getRawFullId(), mth.getMethodInfo().getRawFullId(), clsName);
                        renameDeclaringClass(mth.getParentClass(), clsName);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean methodMatches(MethodInfo call, MethodRef ref) {
        if (ref == null) return false;
        if (!Objects.equals(ownerDot(call), ref.ownerDot)) return false;
        if (!call.getName().equals(ref.name)) return false;
        if (!ref.hasSignature) return true;
        String expectedShortId = buildShortId(ref);
        boolean eq = call.getShortId().equals(expectedShortId);
        if (!eq) {
            LOG.debug("google-helpers: shortId mismatch: got={}, expected={}", call.getShortId(), expectedShortId);
        }
        return eq;
    }

    private static String ownerDot(MethodInfo call) {
        return call.getDeclClass().getFullName();
    }

    private static String buildShortId(MethodRef ref) {
        StringBuilder sb = new StringBuilder();
        sb.append(ref.name).append('(');
        for (String a : ref.argTypes) sb.append(a);
        sb.append(')').append(ref.retType);
        return sb.toString();
    }

    private static String refToString(MethodRef ref) {
        if (ref == null) return "<null>";
        return ref.ownerDot + "->" + buildShortId(ref);
    }

    private static void renameDeclaringClass(ClassNode cls, String raw) {
        String name = normalizeClassName(raw).replace('/', '.');
        LOG.info("google-helpers: renaming {} -> {}", cls.getFullName(), name);
        cls.rename(name);
    }

    private static String normalizeClassName(String s) {
        // Accept forms like com/pkg/Cls, Lcom/pkg/Cls;, or dot
        String r = s.trim();
        if (r.startsWith("L") && r.endsWith(";")) {
            r = r.substring(1, r.length() - 1);
        }
        r = r.replace('.', '/');
        return r;
    }

    private static String extractStringArg(InvokeNode inv, int desiredArgIndex, int pos, InsnNode[] insns) {
        int argIdx = inv.getFirstArgOffset() + desiredArgIndex;
        if (argIdx < 0 || argIdx >= inv.getArgsCount()) {
            LOG.debug("google-helpers: desired arg index {} out of bounds (argsCount={}, offset={})",
                    desiredArgIndex, inv.getArgsCount(), inv.getFirstArgOffset());
            return null;
        }
        InsnArg arg = inv.getArg(argIdx);
        // direct wrapped const string
        if (arg.isInsnWrap()) {
            InsnNode wrap = ((InsnWrapArg) arg).getWrapInsn();
            if (wrap.getType() == InsnType.CONST_STR) {
                String s = ((ConstStringNode) wrap).getString();
                LOG.debug("google-helpers: string arg from wrap: {}", s);
                return s;
            }
            LOG.debug("google-helpers: arg is wrap of {} (not string)", wrap.getType());
        }
        // simple backtrack through previous assignments / moves
        if (arg.isRegister()) {
            int reg = ((RegisterArg) arg).getRegNum();
            LOG.debug("google-helpers: tracing string from register v{}", reg);
            return traceStringFromRegister(reg, pos, insns, 10);
        }
        LOG.debug("google-helpers: arg is neither wrap nor register ({}), skipping", arg.getClass().getSimpleName());
        return null;
    }

    private static String traceStringFromRegister(int reg, int startPos, InsnNode[] insns, int limit) {
        int steps = 0;
        for (int i = startPos - 1; i >= 0 && steps < limit; i--, steps++) {
            InsnNode prev = insns[i];
            if (prev == null) {
                LOG.debug("google-helpers: trace skipped null insn at #{}", i);
                continue;
            }
            RegisterArg res = prev.getResult();
            if (res == null || res.getRegNum() != reg) {
                continue;
            }
            if (prev.getType() == InsnType.CONST_STR) {
                String s = ((ConstStringNode) prev).getString();
                LOG.debug("google-helpers: string arg from const: {}", s);
                return s;
            }
            if (prev.getType() == InsnType.MOVE) {
                InsnArg src = prev.getArg(0);
                if (src.isRegister()) {
                    reg = ((RegisterArg) src).getRegNum();
                    LOG.debug("google-helpers: follow move chain to v{}", reg);
                    continue; // follow move chain
                }
                if (src.isInsnWrap()) {
                    InsnNode wrap = ((InsnWrapArg) src).getWrapInsn();
                    if (wrap.getType() == InsnType.CONST_STR) {
                        String s = ((ConstStringNode) wrap).getString();
                        LOG.debug("google-helpers: string arg from move-wrap: {}", s);
                        return s;
                    }
                    LOG.debug("google-helpers: move-wrap is {} (not string)", wrap.getType());
                }
            }
            LOG.debug("google-helpers: non-const producer for v{}: {}", reg, prev.getType());
        }
        LOG.debug("google-helpers: failed to trace string within {} steps", limit);
        return null;
    }

    public static final class MethodRef {
        final String ownerDot; // dotted owner name
        final String name;
        final boolean hasSignature;
        final String[] argTypes; // descriptors like Ljava/lang/String;
        final String retType;

        MethodRef(String ownerDot, String name, boolean hasSignature, String[] argTypes, String retType) {
            this.ownerDot = ownerDot;
            this.name = name;
            this.hasSignature = hasSignature;
            this.argTypes = argTypes;
            this.retType = retType;
        }

        static MethodRef parse(String ref) {
            if (ref == null || ref.isEmpty()) return null;
            String s = ref.trim();
            int arrow = s.indexOf("->");
            if (arrow == -1) {
                // Accept "owner.name" form without signature
                int dot = s.lastIndexOf('.')
                        ;
                if (dot == -1) return null;
                String owner = s.substring(0, dot).replace('/', '.');
                String name = s.substring(dot + 1);
                return new MethodRef(owner, name, false, new String[0], "");
            }
            String owner = s.substring(0, arrow).replace('/', '.');
            String rest = s.substring(arrow + 2);
            int paren = rest.indexOf('(');
            int close = rest.indexOf(')');
            int retStart = close + 1;
            String name = rest.substring(0, paren);
            String args = rest.substring(paren + 1, close);
            String ret = rest.substring(retStart);
            String[] argTypes;
            if (args.isEmpty()) {
                argTypes = new String[0];
            } else {
                // split descriptors
                argTypes = splitTypeList(args);
            }
            return new MethodRef(owner, name, true, argTypes, ret);
        }

        private static String[] splitTypeList(String desc) {
            // parse descriptors list into array
            java.util.List<String> list = new java.util.ArrayList<>();
            int i = 0;
            while (i < desc.length()) {
                char c = desc.charAt(i);
                if (c == 'L') {
                    int semi = desc.indexOf(';', i);
                    list.add(desc.substring(i, semi + 1));
                    i = semi + 1;
                } else if (c == '[') {
                    int start = i;
                    while (desc.charAt(i) == '[') i++;
                    if (desc.charAt(i) == 'L') {
                        int semi = desc.indexOf(';', i);
                        i = semi + 1;
                    } else {
                        i = i + 1; // primitive
                    }
                    list.add(desc.substring(start, i));
                } else {
                    // primitive
                    list.add(String.valueOf(c));
                    i++;
                }
            }
            return list.toArray(new String[0]);
        }
    }

    public static MethodRef discoverGoogleLoggerFactory(RootNode root) {
        try {
            ClassInfo ci = ClassInfo.fromName(root, "com.google.common.flogger.GoogleLogger");
            ClassNode gl = root.resolveClass(ci);
            if (gl == null) {
                LOG.warn("google-helpers: GoogleLogger class not found in input");
                return null;
            }
            ArgType stringType = ArgType.STRING;
            ArgType retType = ArgType.object(ci.getFullName());
            for (MethodNode m : gl.getMethods()) {
                if (!m.getAccessFlags().isStatic()) continue;
                if (m.getArgTypes().size() != 1) continue;
                if (!m.getArgTypes().get(0).equals(stringType)) continue;
                if (!m.getReturnType().equals(retType)) continue;
                MethodInfo mi = m.getMethodInfo();
                String[] args = new String[] { TypeGen.signature(stringType) };
                String ret = TypeGen.signature(retType);
                MethodRef ref = new MethodRef(ci.getFullName(), mi.getName(), true, args, ret);
                LOG.info("google-helpers: discovered GoogleLogger factory: {}->{}", ci.getFullName(), mi.getShortId());
                return ref;
            }
            LOG.warn("google-helpers: GoogleLogger factory method not found");
        } catch (Throwable ignored) {
        }
        return null;
    }
}
