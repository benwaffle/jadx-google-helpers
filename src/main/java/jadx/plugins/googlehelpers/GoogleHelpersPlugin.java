package jadx.plugins.googlehelpers;

import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.api.plugins.pass.impl.SimpleAfterLoadPass;
import jadx.core.dex.nodes.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoogleHelpersPlugin implements JadxPlugin {
    public static final String PLUGIN_ID = "google-helpers";
    private static final Logger LOG = LoggerFactory.getLogger(GoogleHelpersPlugin.class);

    private final GoogleHelpersOptions options = new GoogleHelpersOptions();
    private volatile RenameFromLogsPass.MethodRef cachedFactoryRef;
    private volatile RenameFromLogsPass.MethodRef cachedLocationRef;

	@Override
    public JadxPluginInfo getPluginInfo() {
        return JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
                .name("google-helpers")
                .description("Renames classes in Google APKs based on log strings")
                .homepage("https://github.com/benwaffle/jadx-google-helpers")
                .requiredJadxVersion("1.5.1, r2333")
                .build();
    }

	@Override
    public void init(JadxPluginContext context) {
        LOG.info("google-helpers: init plugin");
        context.registerOptions(options);
        LOG.info("google-helpers: registering decompile pass (targetClass={}, factoryRef={}, locationRef={})",
                options.getTargetClass(), options.getFactoryMethodRef(), options.getLocationMethodRef());
        context.addPass(new RenameFromLogsPass(options));

        JadxGuiContext gui = context.getGuiContext();
        // Auto-run on load for all classes (after load)
        final JadxGuiContext guiCtx = gui; // capture for use inside pass
        context.addPass(new SimpleAfterLoadPass("GoogleHelpersAutoRename", decompiler -> {
            LOG.info("google-helpers: running auto-rename after load");
            var root = decompiler.getRoot();
            // Resolve refs using options or discovery
            RenameFromLogsPass.MethodRef factory = options.getFactoryMethodRef().isEmpty()
                    ? RenameFromLogsPass.discoverGoogleLoggerFactory(root)
                    : RenameFromLogsPass.MethodRef.parse(options.getFactoryMethodRef());
            RenameFromLogsPass.MethodRef location = options.getLocationMethodRef().isEmpty()
                    ? RenameFromLogsPass.discoverILoggerSetLocation(root)
                    : RenameFromLogsPass.MethodRef.parse(options.getLocationMethodRef());
            // cache for later GUI action reuse
            cachedFactoryRef = factory;
            cachedLocationRef = location;
            int renamed = 0;
            for (ClassNode cls : root.getClasses(true)) {
                try {
                    if (RenameFromLogsPass.renameClassFromLogs(cls, factory, location)) {
                        renamed++;
                    }
                } catch (Throwable t) {
                    LOG.debug("google-helpers: auto-rename error for {}: {}", cls.getFullName(), t.toString());
                }
            }
            if (renamed > 0) {
                LOG.info("google-helpers: auto-rename completed, renamed {} classes", renamed);
                // update packages after renames
                root.runPackagesUpdate();
                if (guiCtx != null) {
                    guiCtx.uiRun(guiCtx::reloadAllTabs);
                }
            } else {
                LOG.info("google-helpers: auto-rename completed, no matches");
            }
        }));

        if (gui != null) {
            // Code editor context menu (always enabled)
            gui.addPopupMenuAction("Google helpers: Rename class from logs", ref -> true, null, ref -> {
                LOG.info("google-helpers: popup action invoked");
                JavaNode jNode = context.getDecompiler().getJavaNodeByRef(ref);
                if (jNode == null) return;
                JavaClass jCls = (jNode instanceof JavaClass) ? (JavaClass) jNode : jNode.getDeclaringClass();
                if (jCls == null) return;
                ClassNode cls = jCls.getClassNode();
                RenameFromLogsPass.MethodRef factory = resolveFactory(cls);
                RenameFromLogsPass.MethodRef location = resolveLocation(cls);
                boolean changed = RenameFromLogsPass.renameClassFromLogs(cls, factory, location);
                if (changed) {
                    LOG.info("google-helpers: class renamed, refreshing tab");
                    gui.reloadActiveTab();
                } else {
                    LOG.info("google-helpers: no changes made");
                }
            });
        } else {
            LOG.debug("google-helpers: GUI context not available (CLI mode)");
        }
    }

    private RenameFromLogsPass.MethodRef resolveFactory(ClassNode cls) {
        if (!options.getFactoryMethodRef().isEmpty()) {
            return RenameFromLogsPass.MethodRef.parse(options.getFactoryMethodRef());
        }
        RenameFromLogsPass.MethodRef cached = cachedFactoryRef;
        if (cached == null) {
            cached = RenameFromLogsPass.discoverGoogleLoggerFactory(cls.root());
            cachedFactoryRef = cached; // may be null
        }
        return cached;
    }

    private RenameFromLogsPass.MethodRef resolveLocation(ClassNode cls) {
        if (!options.getLocationMethodRef().isEmpty()) {
            return RenameFromLogsPass.MethodRef.parse(options.getLocationMethodRef());
        }
        RenameFromLogsPass.MethodRef cached = cachedLocationRef;
        if (cached == null) {
            cached = RenameFromLogsPass.discoverILoggerSetLocation(cls.root());
            cachedLocationRef = cached; // may be null
        }
        return cached;
    }
}
