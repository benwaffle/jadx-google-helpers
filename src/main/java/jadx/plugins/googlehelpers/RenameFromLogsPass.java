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
    private static ClassNode discoveredILoggerIface; // cache discovered ILogger interface

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
        String locOpt = options.getLocationMethodRef();
        if (locOpt != null && !locOpt.isEmpty()) {
            resolvedLocationRef = MethodRef.parse(locOpt);
            LOG.info("google-helpers: using configured locationRef={}", resolvedLocationRef);
        } else {
            resolvedLocationRef = discoverILoggerSetLocation(root);
            if (resolvedLocationRef != null) {
                LOG.info("google-helpers: discovered locationRef={} on load", resolvedLocationRef);
            } else {
                LOG.info("google-helpers: locationRef not configured and discovery failed");
            }
        }
    }

    @Override
    public boolean visit(ClassNode cls) {
        String target = options.getTargetClass();
        if (target == null || target.isEmpty()) {
            // Skip in decompile pass unless explicitly targeted.
            // Auto-run is handled by the AfterLoad pass.
            LOG.trace("google-helpers: decompile pass skipped (no targetClass set)");
            return true;
        }
        String normTarget = normalizeClassName(target);
        String clsRaw = cls.getRawName();
        String clsFull = cls.getFullName();
        if (!clsRaw.endsWith(normTarget) && !clsFull.equals(normTarget.replace('/', '.'))) {
            return true; // not the targeted class
        }
        LOG.debug("google-helpers: processing class: {}", cls.getFullName());
        MethodRef factoryRef = resolvedFactoryRef;
        MethodRef locationRef = resolvedLocationRef;
//        LOG.debug("google-helpers: using factoryRef={}, locationRef={}",
//                factoryRef != null ? factoryRef : "<auto-none>",
//                locationRef != null ? locationRef : "<not-set>");
        boolean changed = renameClassFromLogs(cls, factoryRef, locationRef);
        if (!changed) {
            LOG.trace("google-helpers: no matching logger calls found for {}", cls.getFullName());
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

        boolean changed = false;

        // First pass: try to match factoryRef in <clinit> and <init>
        if (factoryRef != null) {
            MethodNode clinit = cls.getClassInitMth();
            if (clinit != null) {
                changed |= scanMethodForRename(clinit, factoryRef, null);
            } else {
                // LOG.info("google-helpers: <clinit> not found in {}", cls.getFullName());
            }
            boolean anyCtor = false;
            for (MethodNode m : cls.getMethods()) {
                if (!m.getMethodInfo().isConstructor()) continue;
                anyCtor = true;
                changed |= scanMethodForRename(m, factoryRef, null);
            }
            if (!anyCtor) {
                LOG.debug("google-helpers: no <init> constructors found for {}", cls.getFullName());
            }
        }

        // Second pass: try locationRef across all methods
        if (locationRef != null) {
            MethodNode clinit = cls.getClassInitMth();
            if (clinit != null) {
                changed |= scanMethodForRename(clinit, null, locationRef);
            }
            for (MethodNode m : cls.getMethods()) {
                changed |= scanMethodForRename(m, null, locationRef);
            }
        }
        return changed;
    }

    private static boolean scanMethodForRename(MethodNode mth, MethodRef factoryRef, MethodRef locationRef) {
        String owner = mth.getParentClass().getFullName();
        String mthName = mth.getMethodInfo().getName();
        try {
//            LOG.debug("google-helpers: scanning {} {} (preload isNoCode={})", mthName, mth.getMethodInfo().getRawFullId(), mth.isNoCode());
            mth.load();
        } catch (DecodeException e) {
            LOG.warn("google-helpers: failed to load {} for {}: {}", mthName, owner, e.getMessage());
            return false;
        }
        InsnNode[] insns = mth.getInstructions();
        if ((insns == null || insns.length == 0) && mth.isNoCode()) {
            try {
                LOG.debug("google-helpers: {} has no code after load, trying reload for {}", mthName, owner);
                mth.reload();
                insns = mth.getInstructions();
            } catch (Exception e) {
                LOG.debug("google-helpers: reload failed for {} {}: {}", mthName, owner, e.toString());
            }
        }
        if (insns == null || insns.length == 0) {
            LOG.debug("google-helpers: {} has no instructions for {}", mthName, owner);
            return false;
        }
//        LOG.debug("google-helpers: scanning {} {} ({} insns)", mthName, mth.getMethodInfo().getRawFullId(), insns.length);
        boolean changed = false;
        for (int i = 0; i < insns.length; i++) {
            InsnNode insn = insns[i];
//            LOG.debug("google-helpers: insn #{} {}", i, insn);
            if (!(insn instanceof InvokeNode)) {
                continue;
            }
            InvokeNode inv = (InvokeNode) insn;
            MethodInfo call = inv.getCallMth();
//            LOG.debug("google-helpers: invoke at #{}, call={}", i, call.getRawFullId());
            if (factoryRef != null) {
                boolean match = methodMatches(call, factoryRef);
//                LOG.debug("google-helpers: compare with factoryRef {} => {}", refToString(factoryRef), match);
                if (match) {
                    String clsName = extractStringArg(inv, 0, i, insns);
                    if (clsName != null) {
//                        LOG.info("google-helpers: found factory call {} in {} -> {}",
//                                call.getRawFullId(), mth.getMethodInfo().getRawFullId(), clsName);
                        renameDeclaringClass(mth.getParentClass(), clsName);
                        changed = true;
                        break;
                    }
                }
            }
            if (locationRef != null) {
                boolean match = locationCallMatches(mth.getParentClass().root(), call, locationRef);
//                LOG.debug("google-helpers: compare with locationRef {} => {}", refToString(locationRef), match);
                if (match) {
                    String clsName = extractStringArg(inv, 0, i, insns);
                    if (clsName != null) {
//                        LOG.info("google-helpers: found location call {} in {} -> {}",
//                                call.getRawFullId(), mth.getMethodInfo().getRawFullId(), clsName);
                        renameDeclaringClass(mth.getParentClass(), clsName);
                        changed = true;
                    }
                    String newMthName = extractStringArg(inv, 1, i, insns);
                    if (newMthName != null && renameMethodIfValid(mth, newMthName)) {
                        changed = true;
                    }
                    break;
                }
            }
        }
        return changed;
    }

    private static boolean renameMethodIfValid(MethodNode mth, String rawName) {
        if (mth.getMethodInfo().isConstructor()) return false;
        String name = rawName.trim();
        if (name.isEmpty()) return false;
        if (!isValidJavaIdentifier(name)) {
            LOG.debug("google-helpers: invalid method name '{}' for {}", name, mth.getMethodInfo().getRawFullId());
            return false;
        }
        String cur = mth.getMethodInfo().getName();
        if (cur.equals(name)) return false;
        LOG.info("google-helpers: renaming method {} -> {}", mth.getMethodInfo().getRawFullId(), name);
        mth.rename(name);
        return true;
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_' || c0 == '$')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$')) return false;
        }
        return true;
    }

    private static boolean methodMatches(MethodInfo call, MethodRef ref) {
        if (ref == null) return false;
        if (!Objects.equals(ownerDot(call), ref.ownerDot)) return false;
        if (!call.getName().equals(ref.name)) return false;
        if (!ref.hasSignature) return true;
        String expectedShortId = buildShortId(ref);
        boolean eq = call.getShortId().equals(expectedShortId);
        if (!eq) {
//            LOG.debug("google-helpers: shortId mismatch: got={}, expected={}", call.getShortId(), expectedShortId);
        }
        return eq;
    }

    private static String ownerDot(MethodInfo call) {
        return call.getDeclClass().getFullName();
    }

    private static boolean locationCallMatches(RootNode root, MethodInfo call, MethodRef ref) {
        if (ref == null) return false;
        // 1) name must match
        if (!call.getName().equals(ref.name)) {
//            LOG.debug("google-helpers: location match: name mismatch call={} ref={}", call.getName(), ref.name);
            return false;
        }
        // 2) args must match (ignore return type)
        String shortId = call.getShortId(); // name(args)ret
        int parenOpen = shortId.indexOf('(');
        int parenClose = shortId.indexOf(')');
        if (parenOpen <= 0 || parenClose <= parenOpen) {
//            LOG.debug("google-helpers: location match: bad shortId {}", shortId);
            return false;
        }
        String callArgs = shortId.substring(parenOpen + 1, parenClose);
        String expectedArgs = String.join("", ref.argTypes);
        if (!callArgs.equals(expectedArgs)) {
//            LOG.debug("google-helpers: location match: args mismatch call={} expected={}", callArgs, expectedArgs);
            return false;
        }
        // 3) owner must be ref.ownerDot or a sub-interface/impl of it
        String callOwnerName = ownerDot(call);
        if (callOwnerName.equals(ref.ownerDot)) {
//            LOG.debug("google-helpers: location match: owner equals {}", callOwnerName);
            return true;
        }
        try {
            ClassNode callOwner = root.resolveClass(ClassInfo.fromName(root, callOwnerName));
            ClassNode target = root.resolveClass(ClassInfo.fromName(root, ref.ownerDot));
            if (target == null && discoveredILoggerIface != null) {
                target = discoveredILoggerIface;
            }
            if (callOwner != null && target != null) {
                boolean sub = isSubtypeOf(callOwner, target);
//                LOG.debug("google-helpers: location match: owner subtype {} -> {} = {}", callOwner.getFullName(), target.getFullName(), sub);
                if (sub) return true;
            } else {
                LOG.debug("google-helpers: location match: resolve failed callOwner={} target={}", callOwner, target);
            }
        } catch (Throwable ignore) {
            // ignore and fallthrough
        }
        return false;
    }

    private static boolean isSubtypeOf(ClassNode child, ClassNode target) {
        if (child == target) return true;
        final boolean[] found = { false };
        RootNode root = child.root();
        child.visitSuperTypes((argThis, superType) -> {
            if (found[0]) return; // already found
            if (superType != null && superType.isObject()) {
                try {
                    ClassNode superNode = root.resolveClass(superType);
                    if (superNode == target) {
                        found[0] = true;
                    }
                } catch (Throwable ignore) {
                    // ignore resolution issues
                }
            }
        });
        return found[0];
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
//                LOG.debug("google-helpers: string arg from wrap: {}", s);
                return s;
            }
//            LOG.debug("google-helpers: arg is wrap of {} (not string)", wrap.getType());
        }
        // simple backtrack through previous assignments / moves
        if (arg.isRegister()) {
            int reg = ((RegisterArg) arg).getRegNum();
//            LOG.debug("google-helpers: tracing string from register v{}", reg);
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
//                LOG.debug("google-helpers: trace skipped null insn at #{}", i);
                continue;
            }
            RegisterArg res = prev.getResult();
            if (res == null || res.getRegNum() != reg) {
                continue;
            }
            if (prev.getType() == InsnType.CONST_STR) {
                String s = ((ConstStringNode) prev).getString();
//                LOG.debug("google-helpers: string arg from const: {}", s);
                return s;
            }
            if (prev.getType() == InsnType.MOVE) {
                InsnArg src = prev.getArg(0);
                if (src.isRegister()) {
                    reg = ((RegisterArg) src).getRegNum();
//                    LOG.debug("google-helpers: follow move chain to v{}", reg);
                    continue; // follow move chain
                }
                if (src.isInsnWrap()) {
                    InsnNode wrap = ((InsnWrapArg) src).getWrapInsn();
                    if (wrap.getType() == InsnType.CONST_STR) {
                        String s = ((ConstStringNode) wrap).getString();
//                        LOG.debug("google-helpers: string arg from move-wrap: {}", s);
                        return s;
                    }
//                    LOG.debug("google-helpers: move-wrap is {} (not string)", wrap.getType());
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

    public static MethodRef discoverILoggerSetLocation(RootNode root) {
        try {
            // 1) Find AbstractLogger
            ClassInfo alInfo = ClassInfo.fromName(root, "com.google.common.flogger.AbstractLogger");
            ClassNode al = root.resolveClass(alInfo);
            if (al == null) {
                LOG.warn("google-helpers: AbstractLogger class not found in input");
                return null;
            }
            // 2) Try to infer ILogger type from a method taking java.util.logging.Level and returning an interface
            ArgType levelType = ArgType.object("java.util.logging.Level");
            ClassNode iLoggerIface = null;
            for (MethodNode m : al.getMethods()) {
                if (m.getArgTypes().size() == 1 && m.getArgTypes().get(0).equals(levelType)) {
                    ArgType ret = m.getReturnType();
                    if (ret.isObject()) {
                        String obj = ret.getObject();
                        if (obj != null) {
                            ClassInfo ri = ClassInfo.fromName(root, obj);
                            ClassNode rc = root.resolveClass(ri);
                            if (rc != null && rc.getAccessFlags().isInterface()) {
                                iLoggerIface = rc; // keep direct reference to avoid rename issues
                                LOG.info("google-helpers: discovered ILogger candidate via return type: {}", rc.getFullName());
                                break;
                            }
                        }
                    }
                }
            }
            if (iLoggerIface == null) {
                LOG.warn("google-helpers: failed to discover ILogger via AbstractLogger methods");
                return null;
            }

            // 3) In ILogger interface, find setLocation-like method with (String, String, int, String) signature
            ClassNode iloggerCls = iLoggerIface;
            ArgType str = ArgType.STRING;
            ArgType i32 = ArgType.INT;
            for (MethodNode m : iloggerCls.getMethods()) {
				LOG.debug("google-helpers: checking {} for setLocation", m);
                if (m.getArgTypes().size() == 4
                        && m.getArgTypes().get(0).equals(str)
                        && m.getArgTypes().get(1).equals(str)
                        && m.getArgTypes().get(2).equals(i32)
                        && m.getArgTypes().get(3).equals(str)) {
                    // Require methods that return ILogger (no void). Compare by resolved class to handle renames.
                    ArgType ret = m.getReturnType();
                    boolean returnsILogger = false;
                    if (ret.isObject()) {
                        try {
                            ClassNode retCls = root.resolveClass(ClassInfo.fromName(root, ret.getObject()));
                            returnsILogger = (retCls == iloggerCls);
                        } catch (Throwable ignore) {
                            // fallback to name equality if resolution fails
                            returnsILogger = iloggerCls.getFullName().equals(ret.getObject());
                        }
                    }
                    if (!returnsILogger) {
						LOG.debug("google-helpers: method {} has non-ILogger return {}", m, ret);
                        continue;
                    }
                    MethodInfo mi = m.getMethodInfo();
                    String[] args = new String[] {
                            TypeGen.signature(str),
                            TypeGen.signature(str),
                            TypeGen.signature(i32),
                            TypeGen.signature(str)
                    };
                    String retSig = TypeGen.signature(ArgType.object(iloggerCls.getFullName()));
                    MethodRef ref = new MethodRef(iloggerCls.getFullName(), mi.getName(), true, args, retSig);
                    discoveredILoggerIface = iloggerCls; // save for later owner checks
                    LOG.info("google-helpers: discovered ILogger.setLocation candidate: {}->{}", iloggerCls.getFullName(), mi.getShortId());
                    return ref;
                }
            }
            LOG.warn("google-helpers: setLocation-like method not found in ILogger {}", iloggerCls.getFullName());
        } catch (Throwable t) {
            LOG.debug("google-helpers: discoverILoggerSetLocation failed: {}", t.toString());
        }
        return null;
    }
}
