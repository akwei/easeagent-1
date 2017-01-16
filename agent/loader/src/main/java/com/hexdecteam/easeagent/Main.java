package com.hexdecteam.easeagent;

import org.springframework.boot.loader.LaunchedURLClassLoader;
import org.springframework.boot.loader.archive.Archive;
import org.springframework.boot.loader.archive.JarFileArchive;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.Attributes;

/** A main class of java agent to load {@link Agent} service with a {@link LaunchedURLClassLoader}. */
public class Main {
    private static final ClassLoader BOOTSTRAP_CLASS_LOADER = null;
    private static final String      LIB                    = "lib/";
    private static final String      LOGGING_PROPERTY       = "Logging-Property";
    private static final String      EASEAGENT_LOG_CONF     = "easeagent.log.conf";

    public static void premain(final String args, final Instrumentation inst) throws Exception {
        final JarFileArchive archive = new JarFileArchive(getArchiveFileContains(Main.class));
        final Attributes attributes = archive.getManifest().getMainAttributes();
        final String loggingProperty = attributes.getValue(LOGGING_PROPERTY);
        final String bootstrap = attributes.getValue("Bootstrap-Class");
        switchLoggingProperty(loggingProperty, EASEAGENT_LOG_CONF, new Callable<Void>() {

            @Override
            public Void call() throws Exception {
                final URL[] urls = nestArchiveUrls(archive);
                final ClassLoader loader = new LaunchedURLClassLoader(urls, BOOTSTRAP_CLASS_LOADER);
                final Class<?> aClass = loader.loadClass(bootstrap);
                aClass.getMethod("premain", String.class, Instrumentation.class).invoke(null, args, inst);
                return null;
            }
        });
    }

    /**
     * Switching the system property temporary could fix the problem of conflict of logging configuration
     * when host used the same logging library as agent.
     */
    private static void switchLoggingProperty(String hostKey, String agentKey, Callable<Void> callable)
            throws Exception {
        final String host = System.getProperty(hostKey);
        final String agent = System.getProperty(agentKey, "log4j2.xml");
        // Redirect config of host to agent
        System.setProperty(hostKey, agent);
        try {
            callable.call();
        } finally {
            // Recovery host configuration
            if (host == null) {
                System.getProperties().remove(hostKey);
            } else {
                System.setProperty(hostKey, host);
            }
        }

    }

    private static URL[] nestArchiveUrls(Archive archive) throws IOException {
        final List<Archive> archives = archive.getNestedArchives(new Archive.EntryFilter() {
            @Override
            public boolean matches(Archive.Entry e) {
                return !e.isDirectory() && e.getName().startsWith(LIB);
            }
        });

        final URL[] urls = new URL[archives.size()];

        for (int i = 0; i < urls.length; i++) {
            urls[i] = archives.get(i).getUrl();
        }

        return urls;
    }

    private static File getArchiveFileContains(Class<?> klass) throws URISyntaxException {
        ProtectionDomain protectionDomain = klass.getProtectionDomain();
        CodeSource codeSource = protectionDomain.getCodeSource();
        URI location = (codeSource == null ? null : codeSource.getLocation().toURI());
        String path = (location == null ? null : location.getSchemeSpecificPart());
        if (path == null) {
            throw new IllegalStateException("Unable to determine code source archive");
        }
        File root = new File(path);
        if (!root.exists() || root.isDirectory()) {
            throw new IllegalStateException(
                    "Unable to determine code source archive from " + root);
        }
        return root;
    }

}
