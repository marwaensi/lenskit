/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2012 Regents of the University of Minnesota and contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.eval.config;

import com.google.common.base.Preconditions;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.apache.commons.lang3.builder.Builder;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.grouplens.lenskit.eval.EvalEnvironment;
import org.grouplens.lenskit.eval.EvalTask;
import org.grouplens.lenskit.eval.EvaluatorConfigurationException;
import org.grouplens.lenskit.util.io.LKFileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.*;

/**
 * Load and process configuration files. Also provides helper methods used by the
 * configuration scripts to locate & invoke methods.
 * @author Michael Ekstrand
 * @since 0.10
 */
public class EvalConfigEngine {
    private static Logger logger = LoggerFactory.getLogger(EvalConfigEngine.class);
    private static final String METHOD_PATH = "META-INF/lenskit-eval/methods/";

    protected ClassLoader classLoader;
    protected GroovyShell shell;

    private ThreadLocal<List<EvalTask>> taskAccum = new ThreadLocal<List<EvalTask>>();

    @SuppressWarnings("rawtypes")
    private final Map<Class, Class> builders = new HashMap<Class, Class>();

    public EvalConfigEngine() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public EvalConfigEngine(ClassLoader loader) {
        CompilerConfiguration config = new CompilerConfiguration(CompilerConfiguration.DEFAULT);
        config.setScriptBaseClass("org.grouplens.lenskit.eval.config.EvalConfigScript");

        ImportCustomizer imports = new ImportCustomizer();
        imports.addStarImports("org.grouplens.lenskit",
                               "org.grouplens.lenskit.params",
                               "org.grouplens.lenskit.baseline",
                               "org.grouplens.lenskit.norm",
                               "org.grouplens.lenskit.eval.metrics.predict",
                               "org.grouplens.lenskit.eval.metrics.recommend");
        config.addCompilationCustomizers(imports);

        shell = new GroovyShell(loader, new Binding(), config);
        classLoader = loader;

        loadBuilders();
    }

    /**
     * Load a script from a file.
     * @param file The file to read.
     * @return The script as parsed and compiled by Groovy.
     * @throws IOException if the file cannot be read.
     */
    protected EvalConfigScript loadScript(File file) throws IOException {
        EvalConfigScript script =  (EvalConfigScript) shell.parse(file);
        script.setEngine(this);
        return script;
    }

    /**
     * Load a script from a reader.
     * @param in The reader to read.
     * @return The script as parsed and compiled by Groovy.
     */
    protected EvalConfigScript loadScript(Reader in) {
        EvalConfigScript script = (EvalConfigScript) shell.parse(in);
        script.setEngine(this);
        return script;
    }

    /**
     * Run an evaluation config script and get the evaluations it produces.
     * @param script The script to run (as loaded by Groovy)
     * @return A list of evaluations produced by {@code script}.
     * @throws EvaluatorConfigurationException if the script is invalid or produces an error.
     */
    protected EvalEnvironment runScript(EvalConfigScript script) throws EvaluatorConfigurationException {
        Object result = null;
        List<EvalTask> tasks;
        try {
            taskAccum.set(new LinkedList<EvalTask>());
            result = script.run();
            tasks = taskAccum.get();
        } catch (RuntimeException e) {
            throw new EvaluatorConfigurationException("error running configuration script", e);
        } catch (LinkageError e) {
            throw new EvaluatorConfigurationException("error running configuration script", e);
        } finally {
            taskAccum.set(null);
        }
        return new EvalEnvironment(tasks, result);
    }

    /**
     * Load a set of evaluations from a script file.
     * @param file A Groovy script to configure the evaluator.
     * @return A list of evaluations to run.
     * @throws EvaluatorConfigurationException if there is a configuration error
     * @throws IOException if there is an error reading the file
     */
    public EvalEnvironment load(File file) throws EvaluatorConfigurationException, IOException {
        logger.debug("loading script file {}", file);
        return runScript(loadScript(file));
    }

    /**
     * Load a set of evaluations from an input stream.
     * @param in The input stream
     * @return A list of evaluations
     * @throws EvaluatorConfigurationException if there is a configuration error
     */
    public EvalEnvironment load(Reader in) throws EvaluatorConfigurationException {
        return runScript(loadScript(in));
    }

    /**
     * Register a task in the currently-being-evaluated script. Does nothing unless called from
     * within a sript run (inside {@link #runScript(EvalConfigScript)}).
     * @param task The task to register.
     */
    public void registerTask(EvalTask task) {
        List<EvalTask> taskList = taskAccum.get();
        logger.debug("registering task {}", task.getName());
        if (taskList != null) {
            taskList.add(task);
        }
    }

    /**
     * Find a builder factory with a particular name if it exists.
     * @param name The name of the builder
     * @return The builder factory or {@code null} if no such factory exists.
     */
    @CheckForNull @Nullable
    public Class<? extends Builder> getBuilder(@Nonnull String name) {
        String path = METHOD_PATH + name + ".properties";
        logger.debug("loading method {} from {}", name, path);
        InputStream istr = classLoader.getResourceAsStream(path);
        if (istr == null) {
            logger.debug("path {} not found", path);
            return null;
        }

        try {
            Properties props = new Properties();
            props.load(istr);
            String className = props.get("builder").toString();
            if (className == null) return null;

            return Class.forName(className).asSubclass(Builder.class);
        } catch (IOException e) {
            throw new RuntimeException("error reading method " + name, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("cannot find builder class", e);
        } finally {
            LKFileUtils.close(istr);
        }
    }

    /**
     * Get a builder for a type. It consults registered builders and looks for the
     * {@link DefaultBuilder} annotation.
     * @param type A type that needs to be built.
     * @return A builder class to build {@code type}, or {@code null} if none can be found.
     * @see #registerBuilder(Class, Class)
     */
    @SuppressWarnings("unchecked")
    public <T> Class<? extends Builder<? extends T>> getBuilderForType(Class<T> type) {
        @SuppressWarnings("rawtypes")
        Class builder = builders.get(type);
        if (builder == null) {
            DefaultBuilder annot = type.getAnnotation(DefaultBuilder.class);
            if (annot != null) {
                builder = annot.value();
            }
        }
        return builder;
    }

    /**
     * Register a builder class for a type. Used to allow builders to be found for types where
     * the type cannot be augmented with the {@code DefaultBuilder} annotation.
     * @param type The type to build.
     * @param builder A class that can build instances of {@code type}.
     * @param <T> The type to build (type parameter).
     */
    public <T> void registerBuilder(Class<T> type, Class<? extends Builder<? extends T>> builder) {
        Preconditions.checkNotNull(type, "type cannot be null");
        Preconditions.checkNotNull(builder, "builder cannot be null");
        builders.put(type, builder);
    }

    /**
     * Register a default set of builders.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void loadBuilders() {
        Properties props = new Properties();
        try {
            for (URL url: Collections.list(classLoader.getResources("META-INF/lenskit-eval/builders.properties"))) {
                InputStream istr = url.openStream();
                try {
                    props.load(istr);
                } finally {
                    LKFileUtils.close(istr);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (Map.Entry<Object,Object> prop: props.entrySet()) {
            String name = prop.getKey().toString();
            String builder = prop.getValue().toString();
            Class cls;
            try {
                cls = Class.forName(name);
            } catch (ClassNotFoundException e) {
                logger.warn("builder registered for nonexistent class {}", name);
                continue;
            }
            Class bld;
            try {
                bld = Class.forName(builder).asSubclass(Builder.class);
            } catch (ClassNotFoundException e) {
                logger.error("builder class {} not found", builder);
                continue;
            } catch (ClassCastException e) {
                logger.error("class {} is not a builder", builder);
                continue;
            }
            logger.debug("registering {} as builder for {}", bld, cls);
            registerBuilder(cls, bld);
        }
    }
}
