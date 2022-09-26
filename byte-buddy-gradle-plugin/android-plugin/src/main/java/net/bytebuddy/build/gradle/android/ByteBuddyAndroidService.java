/*
 * Copyright 2014 - Present Rafael Winterhalter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bytebuddy.build.gradle.android;

import com.android.build.gradle.BaseExtension;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.ClassVisitorFactory;
import net.bytebuddy.build.AndroidDescriptor;
import net.bytebuddy.build.BuildLogger;
import net.bytebuddy.build.EntryPoint;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.build.gradle.common.GradleBuildLogger;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.QueueFactory;
import net.bytebuddy.utility.nullability.MaybeNull;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.services.BuildServiceSpec;
import org.objectweb.asm.ClassVisitor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * A {@link BuildService} for use with the Byte Buddy Android plugin.
 */
public abstract class ByteBuddyAndroidService implements BuildService<ByteBuddyAndroidService.Parameters>, Closeable {

    /**
     * A {@link ClassVisitorFactory} to bridge between Android and Byte Buddy's ASM namespace.
     */
    private final ClassVisitorFactory<ClassVisitor> classVisitorFactory = ClassVisitorFactory.of(ClassVisitor.class);

    /**
     * The current state of Byte Buddy after initialization or {@code null} if the service is not yet initialized.
     */
    @MaybeNull
    private volatile State state;

    /**
     * Initializes the service.
     *
     * @param parameters The Byte Buddy instrumentation parameters.
     */
    public void initialize(ByteBuddyInstrumentationParameters parameters) {
        if (state != null) {
            return;
        }
        synchronized (this) {
            if (state != null) {
                return;
            }
            try {
                List<ClassFileLocator> classFileLocators = new ArrayList<ClassFileLocator>();
                classFileLocators.add(ClassFileLocator.ForClassLoader.of(ByteBuddy.class.getClassLoader()));
                for (File artifact : parameters.getRuntimeClasspath()
                        .plus(parameters.getAndroidBootClasspath())
                        .plus(parameters.getByteBuddyClasspath())
                        .plus(parameters.getLocalClassesDirectories())
                        .getFiles()) {
                    classFileLocators.add(artifact.isFile()
                            ? ClassFileLocator.ForJarFile.of(artifact)
                            : new ClassFileLocator.ForFolder(artifact));
                }
                ClassFileVersion classFileVersion = ClassFileVersion.ofJavaVersionString(getParameters()
                        .getJavaTargetCompatibilityVersion()
                        .get()
                        .toString());
                ClassFileLocator classFileLocator = new ClassFileLocator.Compound(classFileLocators);
                TypePool typePool = Plugin.Engine.PoolStrategy.Default.FAST.typePool(classFileLocator);
                ClassLoader classLoader = new URLClassLoader(
                        toUrls(parameters.getByteBuddyClasspath().getFiles()),
                        new URLClassLoader(toUrls(parameters.getAndroidBootClasspath().getFiles()), ByteBuddy.class.getClassLoader()));
                AndroidDescriptor androidDescriptor = DefaultAndroidDescriptor.ofClassPath(parameters.getLocalClassesDirectories().getFiles());
                ArrayList<Plugin.Factory> factories = new ArrayList<Plugin.Factory>();
                for (String name : Plugin.Engine.Default.scan(classLoader)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Class<? extends Plugin> type = (Class<? extends Plugin>) Class.forName(name, false, classLoader);
                        if (!Plugin.class.isAssignableFrom(type)) {
                            throw new GradleException(type.getName() + " does not implement " + Plugin.class.getName());
                        }
                        Logger gradleLogger = getParameters().getGradleLogger().get();
                        factories.add(new Plugin.Factory.UsingReflection(type)
                                .with(Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(AndroidDescriptor.class, androidDescriptor))
                                .with(Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(BuildLogger.class, new GradleBuildLogger(gradleLogger)))
                                .with(Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(org.slf4j.Logger.class, gradleLogger))
                        );
                    } catch (Throwable throwable) {
                        throw new IllegalStateException("Cannot resolve plugin: " + name, throwable);
                    }
                }
                List<Plugin> plugins = new ArrayList<Plugin>(factories.size());
                for (Plugin.Factory factory : factories) {
                    Plugin plugin = factory.make();
                    if (plugin instanceof Plugin.WithInitialization) {
                        ((Plugin.WithInitialization) plugin).initialize(classFileLocator);
                    }
                    plugins.add(plugin);
                }
                EntryPoint entryPoint = new EntryPoint.Unvalidated(EntryPoint.Default.DECORATE);
                ByteBuddy byteBuddy = entryPoint.byteBuddy(classFileVersion);
                state = new State(plugins,
                        new Plugin.Engine.TypeStrategy.ForEntryPoint(entryPoint, MethodNameTransformer.Suffixing.withRandomSuffix()),
                        byteBuddy,
                        typePool,
                        classFileLocator,
                        classLoader,
                        androidDescriptor);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    /**
     * Matches a type name for being instrumented.
     *
     * @param name The name of the matched type.
     * @return {@code true} if the type with the given name should be instrumented.
     */
    public boolean matches(String name) {
        State state = this.state;
        if (state == null) {
            throw new IllegalStateException("Byte Buddy Android service was not initialized");
        }
        try {
            TypeDescription typeDescription = state.getTypePool().describe(name).resolve();
            for (Plugin plugin : state.getPlugins()) {
                if (plugin instanceof Plugin.WithPreprocessor) {
                    ((Plugin.WithPreprocessor) plugin).onPreprocess(typeDescription, state.getClassFileLocator());
                }
            }
            for (Plugin plugin : state.getPlugins()) {
                if (plugin.matches(typeDescription)) {
                    return true;
                }
            }
            return false;
        } catch (TypePool.Resolution.NoSuchTypeException ignored) {
            // There are android generated classes for android XML resources, that typically end with "R$[something]",
            // such as "R$layout, R$string, R$id", etc. Which are not available in the classpath by the time this
            // task runs, and also, those classes aren't worthy of instrumentation either.
            return false;
        }
    }

    /**
     * Applies an instrumentation for a given type.
     *
     * @param name         The name of the type being matched.
     * @param classVisitor The class visitor to wrap the instrumentation around.
     * @return A class visitor that includes the applied instrumentation.
     */
    public ClassVisitor apply(String name, ClassVisitor classVisitor) {
        State state = this.state;
        if (state == null) {
            throw new IllegalStateException("Byte Buddy Android service was not initialized");
        }
        TypeDescription typeDescription = state.getTypePool().describe(name).resolve();
        DynamicType.Builder<?> builder = state.getTypeStrategy().builder(state.getByteBuddy(), typeDescription, state.getClassFileLocator());
        for (Plugin plugin : state.getPlugins()) {
            if (plugin.matches(typeDescription)) {
                builder = plugin.apply(builder, typeDescription, state.getClassFileLocator());
            }
        }
        return classVisitorFactory.wrap(builder.wrap(classVisitorFactory.unwrap(classVisitor)));
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close() throws IOException {
        State state = this.state;
        if (state == null) {
            return;
        }
        for (Plugin plugin : state.getPlugins()) {
            plugin.close();
        }
        state.getTypePool().clear();
        state.getClassFileLocator().close();
        if (state.getClassLoader() instanceof Closeable) {
            ((Closeable) state.getClassLoader()).close();
        }
        this.state = null;
    }

    /**
     * Translates a collection of files to {@link URL}s.
     *
     * @param files The list of files to translate.
     * @return An array of URLs representing the provided files.
     */
    private static URL[] toUrls(Collection<File> files) {
        URL[] url = new URL[files.size()];
        int index = 0;
        for (File file : files) {
            try {
                url[index++] = file.toURI().toURL();
            } catch (MalformedURLException exception) {
                throw new IllegalStateException("Failed to convert file " + file.getAbsolutePath(), exception);
            }
        }
        return url;
    }

    /**
     * A state object for a {@link ByteBuddyAndroidService} to represent its post-initialization values.
     */
    protected static class State {

        /**
         * The plugins being applied.
         */
        private final List<Plugin> plugins;

        /**
         * The type strategy being used.
         */
        private final Plugin.Engine.TypeStrategy typeStrategy;

        /**
         * The Byte Buddy instance to use.
         */
        private final ByteBuddy byteBuddy;

        /**
         * The type pool to use.
         */
        private final TypePool typePool;

        /**
         * The class file locator to use.
         */
        private final ClassFileLocator classFileLocator;

        /**
         * The class loader to use.
         */
        private final ClassLoader classLoader;

        /**
         * Provides Android context information.
         */
        private final AndroidDescriptor androidDescriptor;

        /**
         * Creates a new state representation.
         *
         * @param plugins           The plugins being applied.
         * @param typeStrategy      The type strategy being used.
         * @param byteBuddy         The Byte Buddy instance to use.
         * @param typePool          The type pool to use.
         * @param classFileLocator  The class file locator to use.
         * @param classLoader       The class loader to use.
         * @param androidDescriptor The Android descriptor to use.
         */
        protected State(List<Plugin> plugins,
                        Plugin.Engine.TypeStrategy typeStrategy,
                        ByteBuddy byteBuddy,
                        TypePool typePool,
                        ClassFileLocator classFileLocator,
                        ClassLoader classLoader,
                        AndroidDescriptor androidDescriptor) {
            this.plugins = plugins;
            this.typeStrategy = typeStrategy;
            this.byteBuddy = byteBuddy;
            this.typePool = typePool;
            this.classFileLocator = classFileLocator;
            this.classLoader = classLoader;
            this.androidDescriptor = androidDescriptor;
        }

        /**
         * Returns the plugins being applied.
         *
         * @return The plugins being applied.
         */
        protected List<Plugin> getPlugins() {
            return plugins;
        }

        /**
         * Returns the type strategy to use.
         *
         * @return The type strategy to use.
         */
        protected Plugin.Engine.TypeStrategy getTypeStrategy() {
            return typeStrategy;
        }

        /**
         * Returns the Byte Buddy instance to use.
         *
         * @return The Byte Buddy instance to use.
         */
        protected ByteBuddy getByteBuddy() {
            return byteBuddy;
        }

        /**
         * Returns the type pool to use.
         *
         * @return The type pool to use.
         */
        protected TypePool getTypePool() {
            return typePool;
        }

        /**
         * Returns the class file locator to use.
         *
         * @return The class file locator to use.
         */
        protected ClassFileLocator getClassFileLocator() {
            return classFileLocator;
        }

        /**
         * Returns the class loader to use.
         *
         * @return The class loader to use.
         */
        protected ClassLoader getClassLoader() {
            return classLoader;
        }

        /**
         * Returns The Android context information provider.
         *
         * @return The Android context information provider.
         */
        protected AndroidDescriptor getAndroidDescriptor() {
            return androidDescriptor;
        }
    }

    /**
     * A configuration action for the {@link BuildServiceSpec} of the {@link Parameters} of {@link ByteBuddyAndroidService}.
     */
    protected static class ConfigurationAction implements Action<BuildServiceSpec<Parameters>> {

        /**
         * The base extension.
         */
        private final BaseExtension extension;

        /**
         * Gradle's logger.
         */
        private final Logger gradleLogger;

        /**
         * Creates a new configuration action.
         *
         * @param extension    The base extension.
         * @param gradleLogger Gradle's logger.
         */
        protected ConfigurationAction(BaseExtension extension, Logger gradleLogger) {
            this.extension = extension;
            this.gradleLogger = gradleLogger;
        }

        /**
         * {@inheritDoc}
         */
        public void execute(BuildServiceSpec<Parameters> spec) {
            spec.getParameters()
                    .getJavaTargetCompatibilityVersion()
                    .set(extension.getCompileOptions().getTargetCompatibility());
            spec.getParameters().getGradleLogger().set(gradleLogger);
        }
    }

    /**
     * An implementation for an Android descriptor based on resolving class names against the class path.
     */
    protected static class DefaultAndroidDescriptor implements AndroidDescriptor {

        /**
         * The file name extension of a Java class file.
         */
        private static final String CLASS_FILE_EXTENSION = ".class";

        /**
         * The files on the class path.
         */
        private final Set<String> names;

        /**
         * Creates a default Android descriptor.
         *
         * @param names The names of all classes on the class path.
         */
        protected DefaultAndroidDescriptor(Set<String> names) {
            this.names = names;
        }

        /**
         * Resolves class names of a set of class files from the class path.
         *
         * @param roots The class path roots to resolve.
         * @return A suitable Android descriptor.
         */
        protected static AndroidDescriptor ofClassPath(Set<File> roots) {
            Set<String> names = new HashSet<String>();
            for (File root : roots) {
                Queue<File> queue = QueueFactory.make(Collections.singleton(root));
                while (!queue.isEmpty()) {
                    File file = queue.remove();
                    if (file.isDirectory()) {
                        File[] value = file.listFiles();
                        if (value != null) {
                            queue.addAll(Arrays.asList(value));
                        }
                    } else if (file.getName().endsWith(CLASS_FILE_EXTENSION)) {
                        String path = root.getAbsoluteFile().toURI().relativize(file.getAbsoluteFile().toURI()).getPath();
                        names.add(path.substring(0, path.length() - CLASS_FILE_EXTENSION.length()).replace('/', '.'));
                    }
                }
            }
            return new DefaultAndroidDescriptor(names);
        }

        /**
         * {@inheritDoc}
         */
        public TypeScope getTypeScope(TypeDescription typeDescription) {
            return names.contains(typeDescription.getName())
                    ? TypeScope.LOCAL
                    : TypeScope.EXTERNAL;
        }
    }

    /**
     * The parameters that are supplied to {@link ByteBuddyAndroidService}.
     */
    public interface Parameters extends BuildServiceParameters {

        /**
         * Returns the Java target compatibility version.
         *
         * @return The Java target compatibility version.
         */
        Property<JavaVersion> getJavaTargetCompatibilityVersion();

        /**
         * Returns Gradle's logger.
         *
         * @return Gradle's logger.
         */
        Property<Logger> getGradleLogger();
    }
}
