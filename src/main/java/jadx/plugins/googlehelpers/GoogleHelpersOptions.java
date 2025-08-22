package jadx.plugins.googlehelpers;

import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

public class GoogleHelpersOptions extends BasePluginOptionsBuilder {
    private String targetClass = ""; // process only this class if set (dot or slash separated)

    // Full method refs like: com/google/common/flogger/GoogleLogger->c(Ljava/lang/String;)Lcom/google/common/flogger/GoogleLogger;
    private String factoryMethodRef = "";

    // e.g.: a/b/C->x(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V
    private String locationMethodRef = "";

    @Override
    public void registerOptions() {
        strOption(GoogleHelpersPlugin.PLUGIN_ID + ".targetClass")
                .description("class to process (optional), e.g. a.b.C or a/b/C")
                .defaultValue("")
                .setter(v -> targetClass = v);

        strOption(GoogleHelpersPlugin.PLUGIN_ID + ".factoryMethodRef")
                .description("factory method ref\ne.g. com/google/common/flogger/GoogleLogger->c(Ljava/lang/String;)Lcom/google/common/flogger/GoogleLogger;")
                .defaultValue("")
                .setter(v -> factoryMethodRef = v);

        strOption(GoogleHelpersPlugin.PLUGIN_ID + ".locationMethodRef")
                .description("location method ref\ne.g. a/b/C->x(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V")
                .defaultValue("")
                .setter(v -> locationMethodRef = v);
    }

    public String getTargetClass() {
        return targetClass;
    }

    public String getFactoryMethodRef() {
        return factoryMethodRef;
    }

    public String getLocationMethodRef() {
        return locationMethodRef;
    }
}
