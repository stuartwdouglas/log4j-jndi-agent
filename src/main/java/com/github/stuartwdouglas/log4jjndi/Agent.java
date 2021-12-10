package com.github.stuartwdouglas.log4jjndi;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            installBootstrapJar(inst);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        TransformationSupport.run(inst);
    }


    /**
     * shamelessly copied from the data dog agent
     */
    private static synchronized void installBootstrapJar(final Instrumentation inst)
            throws IOException, URISyntaxException {
        URL ddJavaAgentJarURL = null;

        // First try Code Source
        final CodeSource codeSource = Agent.class.getProtectionDomain().getCodeSource();

        if (codeSource != null) {
            ddJavaAgentJarURL = codeSource.getLocation();
            final File bootstrapFile = new File(ddJavaAgentJarURL.toURI());

            if (!bootstrapFile.isDirectory()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapFile));
            }
        }

        System.out.println("Could not get bootstrap jar from code source, using -javaagent arg");

        // ManagementFactory indirectly references java.util.logging.LogManager
        // - On Oracle-based JDKs after 1.8
        // - On IBM-based JDKs since at least 1.7
        // This prevents custom log managers from working correctly
        // Use reflection to bypass the loading of the class
        final List<String> arguments = getVMArgumentsThroughReflection();

        String agentArgument = null;
        for (final String arg : arguments) {
            if (arg.startsWith("-javaagent")) {
                if (agentArgument == null) {
                    agentArgument = arg;
                } else {
                    throw new IllegalStateException(
                            "Multiple javaagents specified and code source unavailable, not installing tracing agent");
                }
            }
        }

        if (agentArgument == null) {
            throw new IllegalStateException(
                    "Could not find javaagent parameter and code source unavailable, not installing tracing agent");
        }

        // argument is of the form -javaagent:/path/to/dd-java-agent.jar=optionalargumentstring
        final Matcher matcher = Pattern.compile("-javaagent:([^=]+).*").matcher(agentArgument);

        if (!matcher.matches()) {
            throw new IllegalStateException("Unable to parse javaagent parameter: " + agentArgument);
        }

        final File javaagentFile = new File(matcher.group(1));
        if (!(javaagentFile.exists() || javaagentFile.isFile())) {
            throw new IllegalStateException("Unable to find javaagent file: " + javaagentFile);
        }
        ddJavaAgentJarURL = javaagentFile.toURI().toURL();
        inst.appendToBootstrapClassLoaderSearch(new JarFile(javaagentFile));
    }

    private static List<String> getVMArgumentsThroughReflection() {
        try {
            // Try Oracle-based
            final Class<?> managementFactoryHelperClass =
                    Class.forName("sun.management.ManagementFactoryHelper");

            final Class<?> vmManagementClass = Class.forName("sun.management.VMManagement");

            Object vmManagement;

            try {
                vmManagement =
                        managementFactoryHelperClass.getDeclaredMethod("getVMManagement").invoke(null);
            } catch (final NoSuchMethodException e) {
                // Older vm before getVMManagement() existed
                final Field field = managementFactoryHelperClass.getDeclaredField("jvm");
                field.setAccessible(true);
                vmManagement = field.get(null);
                field.setAccessible(false);
            }

            return (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);

        } catch (final ReflectiveOperationException e) {
            try { // Try IBM-based.
                final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
                final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
                return Arrays.asList(argArray);
            } catch (final ReflectiveOperationException e1) {
                // Fallback to default
                System.out.println(
                        "WARNING: Unable to get VM args through reflection.  A custom java.util.logging.LogManager may not work correctly");

                return ManagementFactory.getRuntimeMXBean().getInputArguments();
            }
        }
    }

}
