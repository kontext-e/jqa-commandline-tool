package com.buschmais.jqassistant.scm.cli;

import java.io.*;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import org.apache.commons.cli.*;

import com.buschmais.jqassistant.core.plugin.api.PluginConfigurationReader;
import com.buschmais.jqassistant.core.plugin.api.PluginRepositoryException;
import com.buschmais.jqassistant.core.plugin.impl.PluginConfigurationReaderImpl;

/**
 * @author jn4, Kontext E GmbH, 23.01.14
 * @author Dirk Mahler
 */
public class Main {

    private static final String ENV_JQASSISTANT_HOME = "JQASSISTANT_HOME";
    private static final String DIRECTORY_PLUGINS = "plugins";

    /**
     * The main method.
     * 
     * @param args
     *            The command line arguments.
     * @throws IOException
     *             If an error occurs.
     */
    public static void main(String[] args) {
        try {
            Options options = gatherOptions();
            CommandLine commandLine = getCommandLine(args, options);
            final Map<String, Object> properties = readProperties(commandLine);
            PluginRepository pluginRepository = getPluginRepository(properties);
            interpretCommandLine(commandLine, options, pluginRepository);
        } catch (CliExecutionException e) {
            Log.getLog().error(e.getMessage());
            System.exit(e.getExitCode());
        }
    }

    /**
     * Initialize the plugin repository.
     * 
     * @param properties
     *            The plugin properties.
     * @return The repository.
     * @throws CliExecutionException
     *             If initialization fails.
     */
    private static PluginRepository getPluginRepository(Map<String, Object> properties) throws CliExecutionException {
        PluginConfigurationReader pluginConfigurationReader = new PluginConfigurationReaderImpl(createPluginClassLoader());
        PluginRepository pluginRepository;
        try {
            pluginRepository = new PluginRepository(pluginConfigurationReader, properties);
        } catch (PluginRepositoryException e) {
            throw new CliExecutionException("Cannot create plugin repositories.", e);
        }
        return pluginRepository;
    }

    /**
     * Gather all options which are supported by the task (i.e. including
     * standard and specific options).
     * 
     * @return The options.
     */
    private static Options gatherOptions() {
        final Options options = new Options();
        gatherTasksOptions(options);
        gatherStandardOptions(options);
        return options;
    }

    /**
     * Gathers the standard options shared by all tasks.
     * 
     * @param options
     *            The standard options.
     */
    @SuppressWarnings("static-access")
    private static void gatherStandardOptions(final Options options) {
        options.addOption(OptionBuilder.withArgName("p").withDescription("Path to property file; default is jqassistant.properties in the class path")
                .withLongOpt("properties").hasArg().create("p"));
        options.addOption(new Option("help", "print this message"));
    }

    /**
     * Gathers the task specific options for all tasks.
     * 
     * @param options
     *            The task specific options.
     */
    private static void gatherTasksOptions(final Options options) {
        for (Task task : com.buschmais.jqassistant.scm.cli.Task.values()) {
            for (Option option : task.getTask().getOptions()) {
                options.addOption(option);
            }
        }
    }

    /**
     * Returns a string containing the names of all supported tasks.
     * 
     * @return The names of all supported tasks.
     */
    private static String gatherTaskNames() {
        final StringBuilder builder = new StringBuilder();
        for (Task task : com.buschmais.jqassistant.scm.cli.Task.values()) {
            builder.append("'").append(task.name().toLowerCase()).append("' ");
        }
        return builder.toString().trim();
    }

    /**
     * Parse the command line and execute the requested task.
     * 
     * @param commandLine
     *            The command line.
     * @param options
     *            The known options.
     * @throws IOException
     *             If an error occurs.
     */
    static void interpretCommandLine(CommandLine commandLine, Options options, PluginRepository pluginRepository) throws CliExecutionException {
        List<String> requestedTasks = commandLine.getArgList();
        if (requestedTasks.isEmpty()) {
            printUsage(options, "A task must be specified, i.e. one  of " + gatherTaskNames());
            System.exit(1);
        }
        for (String requestedTask : requestedTasks) {
            executeTask(requestedTask, options, commandLine, pluginRepository);
        }
    }

    /**
     * Parse the command line
     * 
     * @param args
     *            The arguments.
     * @param options
     *            The known options.
     * @return The command line.
     */
    private static CommandLine getCommandLine(String[] args, Options options) {
        final CommandLineParser parser = new BasicParser();
        CommandLine commandLine = null;
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException e) {
            printUsage(options, e.getMessage());
            System.exit(1);
        }
        return commandLine;
    }

    /**
     * Executes a task.
     * 
     * @param taskName
     *            The task name.
     * @param option
     *            The option.
     * @param commandLine
     *            The command line.
     * @throws IOException
     */
    private static void executeTask(String taskName, Options option, CommandLine commandLine, PluginRepository pluginRepository) throws CliExecutionException {
        final JQATask task = Task.fromName(taskName);
        if (task == null) {
            printUsage(option, "Unknown task " + taskName);
            System.exit(1);
        }
        try {
            task.withStandardOptions(commandLine);
            task.withOptions(commandLine);
        } catch (CliConfigurationException e) {
            printUsage(option, e.getMessage());
            System.exit(1);
        }
        task.initialize(pluginRepository);
        task.run();
    }

    /**
     * Read the plugin properties file if specified on the command line or if it
     * exists on the class path.
     * 
     * @param commandLine
     *            The command line.
     * @return The plugin properties.
     * @throws CliConfigurationException
     *             If an error occurs.
     */
    private static Map<String, Object> readProperties(CommandLine commandLine) throws CliConfigurationException {
        final Properties properties = new Properties();
        InputStream propertiesStream;
        if (commandLine.hasOption("p")) {
            File propertyFile = new File(commandLine.getOptionValue("p"));
            if (!propertyFile.exists()) {
                throw new CliConfigurationException("Property file given by command line does not exist: " + propertyFile.getAbsolutePath());
            }
            try {
                propertiesStream = new FileInputStream(propertyFile);
            } catch (FileNotFoundException e) {
                throw new CliConfigurationException("Cannot open property file.", e);
            }
        } else {
            propertiesStream = Main.class.getResourceAsStream("/jqassistant.properties");
        }
        Map<String, Object> result = new HashMap<>();
        if (propertiesStream != null) {
            try {
                properties.load(propertiesStream);
            } catch (IOException e) {
                throw new CliConfigurationException("Cannot load properties from file.", e);
            }
            for (String name : properties.stringPropertyNames()) {
                result.put(name, properties.getProperty(name));
            }
        }
        return result;
    }

    /**
     * Print usage information.
     * 
     * @param options
     *            The known options.
     * @param errorMessage
     *            The error message to append.
     */
    private static void printUsage(final Options options, final String errorMessage) {
        System.out.println(errorMessage);
        final HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(Main.class.getCanonicalName(), options);
        System.out.println("Example: " + Main.class.getCanonicalName() + " scan -f target/classes,target/test-classes");
    }

    /**
     * Determine the JQASSISTANT_HOME directory.
     *
     * @return The directory or <code>null</code>.
     */
    private static File getHomeDirectory() {
        String dirName = System.getenv(ENV_JQASSISTANT_HOME);
        if (dirName != null) {
            File dir = new File(dirName);
            if (dir.exists()) {
                Log.getLog().debug("Using JQASSISTANT_HOME '" + dir.getAbsolutePath() + "'.");
                return dir;
            } else {
                Log.getLog().warn("JQASSISTANT_HOME '" + dir.getAbsolutePath() + "' points to a non-existing directory.");
                return null;
            }
        }
        Log.getLog().warn("JQASSISTANT_HOME is not set.");
        return null;
    }

    /**
     * Create the class loader to be used for detecting and loading plugins.
     *
     * @return The plugin class loader.
     * @throws com.buschmais.jqassistant.scm.cli.CliExecutionException
     *             If the plugins cannot be loaded.
     */
    private static ClassLoader createPluginClassLoader() throws CliExecutionException {
        ClassLoader parentClassLoader = JQATask.class.getClassLoader();
        File homeDirectory = getHomeDirectory();
        if (homeDirectory != null) {
            File pluginDirectory = new File(homeDirectory, DIRECTORY_PLUGINS);
            if (pluginDirectory.exists()) {
                final List<URL> urls = new ArrayList<>();
                final Path pluginDirectoryPath = pluginDirectory.toPath();
                SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toFile().getName().endsWith(".jar")) {
                            urls.add(file.toFile().toURI().toURL());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                };
                try {
                    Files.walkFileTree(pluginDirectoryPath, visitor);
                } catch (IOException e) {
                    throw new CliExecutionException("Cannot read plugin directory.", e);
                }
                return new PluginClassLoader(urls, parentClassLoader);
            }
        }
        return parentClassLoader;
    }
}
