package com.megaease.easeagent.core;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.megaease.easeagent.core.mbean.CallTraceSetting;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Default;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.FluentIterable.from;
import static com.typesafe.config.ConfigRenderOptions.defaults;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class Bootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(Bootstrap.class);

    public static void start(String args, Instrumentation inst, Iterable<Class<?>> providers,
                             Iterable<Class<? extends Transformation>> transformations) throws Exception {
        final long begin = System.nanoTime();

        LOGGER.debug("Injected class: {}", AppendBootstrapClassLoaderSearch.by(inst));

        final Config conf = load(args);

        if (LOGGER.isDebugEnabled()) {
            final String repr = conf.root().render(defaults().setOriginComments(false).setJson(false));
            LOGGER.debug("Loaded conf:\n{}", repr);
        }

        define(
                conf, transformations, scoped(providers, conf),
                new Default().with(LISTENER)
                             .ignore(any(), protectedLoaders())
                             .or(isInterface())
                             .or(isSynthetic())
                             .or(nameStartsWith("sun.reflect."))
        ).installOn(inst);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName mbeanName = new ObjectName("com.megaease.easeagent.core:type=CallTraceSetting");
        server.registerMBean(new CallTraceSetting(), mbeanName);

        LOGGER.info("Initialization has took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
    }




    private static Map<Class<?>, Iterable<QualifiedBean>> scoped(Iterable<Class<?>> providers, final Config conf) {
        return ImmutableMap.copyOf(Maps.transformValues(
                from(providers).uniqueIndex(new Function<Class<?>, Class<?>>() {
                    @Override
                    public Class<?> apply(Class<?> input) {
                        return input.getSuperclass();
                    }
                }), new Function<Class<?>, Iterable<QualifiedBean>>() {
                    @Override
                    public Iterable<QualifiedBean> apply(Class<?> input) {
                        return beans(conf, input);
                    }
                }));
    }

    private static ElementMatcher<ClassLoader> protectedLoaders() {
        return isBootstrapClassLoader().or(is(Bootstrap.class.getClassLoader()));
    }

    private static AgentBuilder define(Config conf, Iterable<Class<? extends Transformation>> transformations,
                                       Map<Class<?>, Iterable<QualifiedBean>> scopedBeans, AgentBuilder ab) {

        for (Class<? extends Transformation> tc : transformations) {
            final Injection.Provider ann = tc.getAnnotation(Injection.Provider.class);
            final Iterable<QualifiedBean> beans = ann == null ? Collections.<QualifiedBean>emptySet() : scopedBeans.get(ann.value());
            final Register register = new Register(beans);

            for (Map.Entry<ElementMatcher<? super TypeDescription>, Iterable<Definition.Transformer>> entry :
                    newInstance(conf, tc).define(Definition.Default.EMPTY).asMap().entrySet()) {

                ab = ab.type(entry.getKey()).transform(compound(entry.getValue(), register)).asDecorator();
            }

            LOGGER.debug("Defined {}", tc);
        }
        return ab;
    }

    private static AgentBuilder.Transformer compound(Iterable<Definition.Transformer> transformers, final Register register) {
        return new AgentBuilder.Transformer.Compound(from(transformers).transform(
                new Function<Definition.Transformer, AgentBuilder.Transformer>() {
                    @Override
                    public AgentBuilder.Transformer apply(final Definition.Transformer input) {
                        return new ForRegisterAdvice(register, input);
                    }
                }).toList());
    }

    private static Iterable<QualifiedBean> beans(Config conf, Class<?> provider) {
        final ImmutableList.Builder<QualifiedBean> builder = ImmutableList.builder();
        final Object instance = newInstance(conf, provider);
        for (Method method : provider.getMethods()) {
            final Injection.Bean bean = method.getAnnotation(Injection.Bean.class);
            if (bean == null) continue;
            try {
                final QualifiedBean qb = new QualifiedBean(bean.value(), method.invoke(instance));
                builder.add(qb);
                LOGGER.debug("Provided {} ", qb);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return builder.build();
    }

    private static <T> T newInstance(Config conf, Class<T> aClass) {
        final Configurable configurable = aClass.getAnnotation(Configurable.class);
        try {
            return configurable == null ? aClass.newInstance()
                    : aClass.getConstructor(Config.class).newInstance(conf.getConfig(configurable.bind()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Config load(String pathname) {
        return Strings.isNullOrEmpty(pathname)
                ? ConfigFactory.load(Bootstrap.class.getClassLoader())
                : ConfigFactory.parseFile(new File(pathname)).resolve();
    }

    private static final AgentBuilder.Listener LISTENER = new AgentBuilder.Listener() {

        @Override
        public void onTransformation(TypeDescription td, ClassLoader ld, JavaModule m, boolean loaded, DynamicType dt) {
            LOGGER.debug("Transform {} from {}", td, ld);
        }

        @Override
        public void onIgnored(TypeDescription td, ClassLoader ld, JavaModule m, boolean loaded) {
            LOGGER.trace("Ignored {} from {}", td, ld);
        }

        @Override
        public void onError(String name, ClassLoader ld, JavaModule m, boolean loaded, Throwable error) {
            LOGGER.error(name, error);
        }

        @Override
        public void onComplete(String name, ClassLoader ld, JavaModule m, boolean loaded) {
            LOGGER.trace("Complete {} from {}", name, ld);
        }
    };

    private static class ForRegisterAdvice implements AgentBuilder.Transformer {
        private final Register register;
        private final String adviceFactoryClassName;
        private final ForAdvice transformer;

        ForRegisterAdvice(Register register, Definition.Transformer transformer) {
            this.register = register;
            this.adviceFactoryClassName = transformer.adviceFactoryClassName;
            this.transformer = new ForAdvice().include(getClass().getClassLoader())
                                              .advice(transformer.matcher, transformer.inlineAdviceClassName);
        }

        @Override
        public Builder<?> transform(Builder<?> b, TypeDescription td, ClassLoader cl, JavaModule m) {
            register.apply(adviceFactoryClassName, cl);
            return transformer.transform(b, td, cl, m);
        }
    }
}
