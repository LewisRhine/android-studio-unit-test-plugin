package me.tatarka.androidunittest.idea;

import com.android.builder.model.JavaArtifact;
import com.android.builder.model.SourceProvider;
import com.android.builder.model.Variant;
import com.android.tools.idea.gradle.customizer.ModuleCustomizer;
import com.android.tools.idea.gradle.run.MakeBeforeRunTask;
import com.android.tools.idea.gradle.run.MakeBeforeRunTaskProvider;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.Maps;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import me.tatarka.androidunittest.idea.util.DefaultManifestParser;
import me.tatarka.androidunittest.idea.util.ManifestParser;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.android.run.AndroidRunConfiguration;
import org.jetbrains.android.run.AndroidRunConfigurationType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created by evan on 6/7/14.
 */
public class RunConfigurationModuleCustomizer implements ModuleCustomizer<IdeaAndroidUnitTest> {
    private static final Logger LOG = Logger.getInstance(AbstractContentRootModuleCustomizer.class);

    @Override
    public void customizeModule(@NotNull Module module, @NotNull Project project, @Nullable IdeaAndroidUnitTest androidUnitTest) {
        if (androidUnitTest == null) {
            return;
        }
        JavaArtifact selectedTestJavaArtifact = androidUnitTest.getSelectedTestJavaArtifact(module);
        if (selectedTestJavaArtifact == null) {
            return;
        }
        Variant androidVariant = androidUnitTest.getSelectedAndroidVariant(module);

        String RPackageName = findRPackageName(androidUnitTest);

        String vmParameters = buildVmParameters(module, RPackageName, selectedTestJavaArtifact, androidVariant);

        RunManagerEx runManager = RunManagerEx.getInstanceEx(project);
        JUnitConfigurationType junitConfigType = ConfigurationTypeUtil.findConfigurationType(JUnitConfigurationType.class);
        List<RunConfiguration> configs = runManager.getConfigurationsList(junitConfigType);

        for (RunConfiguration config : configs) {
            if (isRelevantRunConfig(module, config, JUnitConfiguration.class)) {
                JUnitConfiguration jconfig = (JUnitConfiguration) config;
                jconfig.setVMParameters(vmParameters);
                setupMakeTask(project, module, androidVariant, runManager, jconfig);
            }
        }

        for (ConfigurationFactory factory : junitConfigType.getConfigurationFactories()) {
            RunnerAndConfigurationSettings settings = runManager.getConfigurationTemplate(factory);
            RunConfiguration config = settings.getConfiguration();
            if (isRelevantRunConfig(module, config, JUnitConfiguration.class)) {
                JUnitConfiguration jconfig = (JUnitConfiguration) config;
                jconfig.setVMParameters(vmParameters);
                setupMakeTask(project, module, androidVariant, runManager, jconfig);
            }
        }
    }

    private static void setupMakeTask(@NotNull Project project, @NotNull Module module, @NotNull Variant androidVariant, @NotNull RunManagerEx runManager, @NotNull JUnitConfiguration jconfig) {
        MakeBeforeRunTask makeBeforeRunTask = null;
        for (BeforeRunTask task : runManager.getBeforeRunTasks(jconfig)) {
            if (task instanceof MakeBeforeRunTask) {
                makeBeforeRunTask = (MakeBeforeRunTask) task;
                break;
            }
            // TODO: disable the default make task
        }

        if (makeBeforeRunTask == null) {
            BeforeRunTaskProvider<MakeBeforeRunTask> makeBeforeRunProvider = BeforeRunTaskProvider.getProvider(project, MakeBeforeRunTaskProvider.ID);
            AndroidRunConfigurationType androidRunConfigType = ConfigurationTypeUtil.findConfigurationType(AndroidRunConfigurationType.class);
            List<RunConfiguration> configs = runManager.getConfigurationsList(androidRunConfigType);

            AndroidRunConfiguration androidRunConfig = null;
            for (RunConfiguration config : configs) {
                if (config instanceof AndroidRunConfiguration) {
                    // Any android run configuration will do, just need one to create the task.
                    androidRunConfig = (AndroidRunConfiguration) config;
                    break;
                }
            }

            if (androidRunConfig != null) {
                makeBeforeRunTask = makeBeforeRunProvider.createTask(androidRunConfig);
            }
        }

        if (makeBeforeRunTask != null) {
            makeBeforeRunTask.setEnabled(true);
            String testBuildTaskName = "test" + StringUtils.capitalize(androidVariant.getName()) + "Classes";
            makeBeforeRunTask.setGoal(testBuildTaskName);
            runManager.setBeforeRunTasks(jconfig, Collections.<BeforeRunTask>singletonList(makeBeforeRunTask), false);
        }
    }

    private static String findRPackageName(IdeaAndroidUnitTest androidUnitTest) {
        String packageName = androidUnitTest.getAndroidDelegate().getDefaultConfig().getProductFlavor().getApplicationId();
        if (packageName == null) {
            File manifestFile = androidUnitTest.getAndroidDelegate().getDefaultConfig().getSourceProvider().getManifestFile();
            ManifestParser parser = new DefaultManifestParser();
            packageName = parser.getPackage(manifestFile);
        }
        return packageName;
    }

    private static boolean isRelevantRunConfig(Module module, RunConfiguration config, Class<? extends ModuleBasedConfiguration> runConfigClass) {
        if (!config.getClass().isAssignableFrom(runConfigClass)) return false;
        for (Module m : ((ModuleBasedConfiguration) config).getModules()) {
            if (m == module) return true;
        }
        return false;
    }

    private static String buildVmParameters(Module module, String RPackageName, JavaArtifact testJavaArtifact, Variant androidVariant) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> prop : getRobolectricProperties(RPackageName, testJavaArtifact).entrySet()) {
            builder.append("-D").append(prop.getKey()).append("=\"").append(prop.getValue()).append("\" ");
        }
        return builder.toString();
    }

    private static Map<String, String> getRobolectricProperties(String RPackageName, JavaArtifact testJavaArtifact) {
        SourceProvider sourceProvider = testJavaArtifact.getVariantSourceProvider();
        String manifestFile = sourceProvider.getManifestFile().getAbsolutePath();
        String resourcesDirs = fileCollectionToPath(sourceProvider.getResDirectories());
        String assetsDir = fileCollectionToPath(sourceProvider.getAssetsDirectories());

        Map<String, String> props = Maps.newHashMap();
        props.put("android.manifest", manifestFile);
        props.put("android.resources", resourcesDirs);
        props.put("android.assets", assetsDir);
        props.put("android.package", RPackageName);
        return props;
    }

    private static String fileCollectionToPath(Collection<File> files) {
        return Joiner.on(File.pathSeparatorChar).join(Collections2.transform(files, new Function<File, String>() {
            @javax.annotation.Nullable
            @Override
            public String apply(@Nullable File file) {
                return file.getAbsolutePath();
            }
        }));
    }
}
