package jadx.plugins.example;

import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.core.dex.nodes.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JadxExamplePlugin implements JadxPlugin {
    public static final String PLUGIN_ID = "google-helpers";
    private static final Logger LOG = LoggerFactory.getLogger(JadxExamplePlugin.class);

    private final GoogleHelpersOptions options = new GoogleHelpersOptions();
    private volatile RenameFromLogsPass.MethodRef cachedFactoryRef;

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
        if (gui != null) {
            // Code editor context menu (always enabled)
            gui.addPopupMenuAction("Google helpers: Rename class from logs", ref -> true, null, ref -> {
                LOG.info("google-helpers: popup action invoked");
                JavaNode jNode = context.getDecompiler().getJavaNodeByRef(ref);
                if (jNode == null) return;
                JavaClass jCls = (jNode instanceof JavaClass) ? (JavaClass) jNode : jNode.getDeclaringClass();
                if (jCls == null) return;
                ClassNode cls = jCls.getClassNode();
                RenameFromLogsPass.MethodRef factory;
                if (!options.getFactoryMethodRef().isEmpty()) {
                    factory = RenameFromLogsPass.MethodRef.parse(options.getFactoryMethodRef());
                } else {
                    RenameFromLogsPass.MethodRef cached = cachedFactoryRef;
                    if (cached == null) {
                        cached = RenameFromLogsPass.discoverGoogleLoggerFactory(cls.root());
                        cachedFactoryRef = cached; // may be null
                    }
                    factory = cached;
                }
                RenameFromLogsPass.MethodRef location = RenameFromLogsPass.MethodRef.parse(options.getLocationMethodRef());
                boolean changed = RenameFromLogsPass.renameClassFromLogs(cls, factory, location);
                if (changed) {
                    LOG.info("google-helpers: class renamed, refreshing tab");
                    gui.reloadActiveTab();
                } else {
                    LOG.info("google-helpers: no changes made");
                }
            });

            // Plugins top menu action (uses caret location)
            gui.addMenuAction("Google helpers: Rename class from logs", () -> {
                LOG.info("google-helpers: menu action invoked");
                ICodeNodeRef ref = gui.getEnclosingNodeUnderCaret();
                if (ref == null) return;
                JavaNode jNode = context.getDecompiler().getJavaNodeByRef(ref);
                if (jNode == null) return;
                JavaClass jCls = (jNode instanceof JavaClass) ? (JavaClass) jNode : jNode.getDeclaringClass();
                if (jCls == null) return;
                ClassNode cls = jCls.getClassNode();
                RenameFromLogsPass.MethodRef factory;
                if (!options.getFactoryMethodRef().isEmpty()) {
                    factory = RenameFromLogsPass.MethodRef.parse(options.getFactoryMethodRef());
                } else {
                    RenameFromLogsPass.MethodRef cached = cachedFactoryRef;
                    if (cached == null) {
                        cached = RenameFromLogsPass.discoverGoogleLoggerFactory(cls.root());
                        cachedFactoryRef = cached;
                    }
                    factory = cached;
                }
                RenameFromLogsPass.MethodRef location = RenameFromLogsPass.MethodRef.parse(options.getLocationMethodRef());
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
}
