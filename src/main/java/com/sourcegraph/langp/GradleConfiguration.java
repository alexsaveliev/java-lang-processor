package com.sourcegraph.langp;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

class GradleConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(GradleConfiguration.class);

    private static final String TASK_CODE_RESOURCE = "/metainfo.gradle";

    private static final String GRADLE_CMD_WINDOWS = "gradle.bat";
    private static final String GRADLE_CMD_OTHER = "gradle";

    private static final String DEFAULT_GROUP_ID = "default-group";

    static boolean prepare(Path path) {
        LOGGER.info("Scanning for Gradle project descriptors in {}", path);
        Collection<Path> descriptors = getDescriptors(path);

        Map<String, LanguageServerConfiguration> configurations = new HashMap<>();
        Map<String, Project> projectsCache = new HashMap<>();

        Set<Path> visited = new HashSet<>();
        for (Path descriptor : descriptors) {

            descriptor = descriptor.toAbsolutePath().normalize();

            if (!visited.add(descriptor)) {
                continue;
            }
            LOGGER.info("Processing {}", descriptor);
            Map<String, Project> projects = processDescriptor(path, descriptor, visited);

            for (Project project : projects.values()) {

                projectsCache.put(project.id(), project);

                for (ProjectDependency projectDependency : project.projectDependencies) {
                    if (!StringUtils.isEmpty(projectDependency.buildFile)) {
                        Path p = path.resolve(projectDependency.buildFile).toAbsolutePath().normalize();
                        visited.add(p);
                    }
                }
                LanguageServerConfiguration configuration = new LanguageServerConfiguration();
                configuration.sources = new LinkedHashSet<>();
                configuration.classPath = new LinkedHashSet<>();
                configuration.outputDirectory = project.outputDir;
                configurations.put(project.id(), configuration);
            }

            for (Map.Entry<String, LanguageServerConfiguration> entry : configurations.entrySet()) {
                LanguageServerConfiguration configuration = entry.getValue();
                Collection<Project> allProjects = collectProjects(entry.getKey(), projectsCache);
                for (Project project : allProjects) {
                    configuration.classPath.addAll(project.classPath);
                    configuration.classPath.addAll(project.dependencies.stream().filter(dependency ->
                            !StringUtils.isEmpty(dependency.file)).map(dependency ->
                            dependency.file).
                            collect(Collectors.toList()));
                    configuration.sources.addAll(project.sourceDirs.stream().
                            map(s -> s.filePath).
                            collect(Collectors.toList()));
                    for (String item : project.classPath) {
                        File file = path.resolve(item).toFile();
                        if (file.isDirectory()) {
                            configuration.sources.add(item);
                        }
                    }
                }
                Project p = projectsCache.get(entry.getKey());
                configuration.write(path, Paths.get(p.projectDir).resolve(".jls-config"));
            }

        }

        return !projectsCache.isEmpty();
    }

    /**
     * Extracts meta information from given Gradle build file
     *
     * @param root       workspace root
     * @param descriptor Gradle build file
     * @param visited    tracks visited files
     */
    private static Map<String, Project> processDescriptor(Path root, Path descriptor, Set<Path> visited) {
        Collection<Project> projects = collectMetaInformation(root, descriptor);
        Map<String, Project> ret = new HashMap<>();
        for (Project project : projects) {
            ret.put(project.groupId + '/' + project.artifactId, project);
        }
        return ret;
    }

    /**
     * Extracts meta information from given Gradle build file
     *
     * @param root       workspace root
     * @param descriptor Gradle build file
     */
    private static Collection<Project> collectMetaInformation(Path root, Path descriptor) {

        try {
            Path wrapper = getWrapper(root, descriptor);

            Path modifiedGradleScriptFile = Files.createTempFile("srclib-collect-meta", "gradle");

            try {
                InputStream inputStream = GradleConfiguration.class.getResourceAsStream(TASK_CODE_RESOURCE);
                OutputStream outputStream = new FileOutputStream(modifiedGradleScriptFile.toString(), false);
                try {
                    IOUtils.copy(inputStream, outputStream);
                } finally {
                    IOUtils.closeQuietly(inputStream);
                    IOUtils.closeQuietly(outputStream);
                }

                Path workDir = descriptor.toAbsolutePath().getParent();
                List<String> gradleArgs = createArguments(wrapper, modifiedGradleScriptFile);
                ProcessBuilder pb = new ProcessBuilder(gradleArgs);

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Running {} using working directory {}",
                            StringUtils.join(gradleArgs, ' '),
                            workDir.normalize());
                }

                pb.directory(new File(workDir.toString()));
                pb.redirectErrorStream(true);
                BufferedReader in = null;
                Collection<Project> results = new ArrayList<>();
                Project project = null;

                try {
                    Process process = pb.start();
                    in = new BufferedReader(new InputStreamReader(process.getInputStream()));

                    String line;
                    StringBuilder output = new StringBuilder();
                    while ((line = in.readLine()) != null) {
                        if ("BUILD FAILED".equals(line)) {
                            LOGGER.error("Failed to process {} - gradle build failed. Output was: {}", descriptor, output);
                            results.clear();
                            break;
                        }
                        String meta[] = parseMeta(line);
                        if (meta == null) {
                            LOGGER.debug("gradle: {}", line);
                            continue;
                        }
                        String prefix = meta[0];
                        String payload = meta[1];
                        switch (prefix) {
                            case "SRCLIB-ARTIFACT":
                                project = new Project();
                                results.add(project);
                                project.artifactId = payload;
                                break;
                            case "SRCLIB-GROUP":
                                if (project == null) {
                                    continue;
                                }
                                project.groupId = groupId(payload);
                                break;
                            case "SRCLIB-CLASSPATH":
                                if (project == null) {
                                    continue;
                                }
                                for (String path : payload.split(SystemUtils.PATH_SEPARATOR)) {
                                    if (!StringUtils.isEmpty(path)) {
                                        project.classPath.add(path);
                                    }
                                }
                                break;
                            case "SRCLIB-SOURCEDIR":
                                if (project == null) {
                                    continue;
                                }
                                String tokens[] = payload.split(":", 3);
                                String unitName = groupId(tokens[0]) + '/' + tokens[1];
                                project.sourceDirs.add(new SourcePathElement(unitName, tokens[2]));
                                break;
                            case "SRCLIB-PROJECTDIR":
                                if (project == null) {
                                    continue;
                                }
                                project.projectDir = payload;
                                break;
                            case "SRCLIB-PROJECTDEPENDENCY":
                                if (project == null) {
                                    continue;
                                }
                                String depTokens[] = payload.split(":", 3);
                                project.projectDependencies.add(new ProjectDependency(groupId(depTokens[0]),
                                        depTokens[1],
                                        depTokens[2]));
                                break;
                            case "SRCLIB-DEPENDENCY":
                                if (project == null) {
                                    continue;
                                }
                                String[] parts = payload.split(":", 5);
                                project.dependencies.add(new Dependency(
                                        groupId(parts[1]), // GroupID
                                        parts[2], // ArtifactID
                                        parts[3], // Version
                                        parts[0], // Scope
                                        parts.length > 4 ? parts[4] : null // file
                                ));
                                break;
                            case "SRCLIB-WARNING":
                                LOGGER.warn("gradle: {}", payload);
                                break;
                            case "SRCLIB-OUTPUTDIR":
                                if (project == null) {
                                    continue;
                                }
                                project.outputDir = payload;
                                break;
                            default:
                                LOGGER.debug("gradle: {}", line);
                                output.append(line).append(IOUtils.LINE_SEPARATOR);
                        }
                    }
                } finally {
                    IOUtils.closeQuietly(in);
                }

                return results;
            } finally {
                Files.deleteIfExists(modifiedGradleScriptFile);
            }
        } catch (IOException ex) {
            LOGGER.warn("An error occurred while extracting metadata", ex);
            return Collections.emptyList();
        }
    }


    /**
     * Collects all Gradle build descriptors in the given path
     *
     * @param path root path
     * @return Gradle build descriptors in the given path
     */
    private static Collection<Path> getDescriptors(Path path) {
        // putting root gradle file first, it may contain references to all the subprojects
        Set<Path> gradleFiles = new LinkedHashSet<>();
        File rootGradleFile = path.resolve("build.gradle").toFile();
        if (rootGradleFile.isFile()) {
            gradleFiles.add(rootGradleFile.toPath().toAbsolutePath().normalize());
        } else {
            // alexsaveliev: trying settings.gradle - build file name may be custom one
            // (see https://github.com/Netflix/archaius)
            rootGradleFile = path.resolve("settings.gradle").toFile();
            if (rootGradleFile.isFile()) {
                gradleFiles.add(rootGradleFile.toPath().toAbsolutePath().normalize());
            }
        }

        try {
            gradleFiles.addAll(ScanUtil.findMatchingFiles(path, "build.gradle"));
        } catch (IOException ex) {
            LOGGER.warn("Failed to scan for Gradle project descriptors", ex);
        }

        return gradleFiles;
    }

    /**
     * @param root       workpace root
     * @param gradleFile Gradle build file to process
     * @return best suitable Gradle command (gradlew in top directory if there is any or regular gradle)
     */
    private static Path getWrapper(Path root, Path gradleFile) {
        // looking for gradle wrapper from build file's path to current working dir
        String gradleExe;
        if (SystemUtils.IS_OS_WINDOWS) {
            gradleExe = "gradlew.bat";
        } else {
            gradleExe = "gradlew";
        }
        Path current = gradleFile.toAbsolutePath().getParent().toAbsolutePath().normalize();
        while (true) {
            Path p = current.resolve(gradleExe);
            File exeFile = p.toFile();
            if (exeFile.exists() && !exeFile.isDirectory()) {
                return p;
            }
            if (current.startsWith(root) && !current.equals(root)) {
                Path parent = current.getParent();
                if (parent == null || parent.equals(current)) {
                    break;
                }
                current = parent;
            } else {
                break;
            }
        }

        return null;
    }

    /**
     * Parses metadata line, expected format is PREFIX-SPACE-CONTENT
     *
     * @param line line to parse
     * @return two-elements array, where first item is a prefix and second item is content
     */
    private static String[] parseMeta(String line) {
        int idx = line.indexOf(' ');
        if (-1 == idx)
            return null;
        return new String[]{line.substring(0, idx), line.substring(idx + 1).trim()};
    }

    /**
     * @param groupId group ID (may be empty)
     * @return normalize group ID (not empty)
     */
    private static String groupId(String groupId) {
        return StringUtils.defaultIfEmpty(groupId, DEFAULT_GROUP_ID);
    }

    /**
     * Assembles arguments for Gradle wrapper
     *
     * @param wrapper Gradle wrapper (may be null - use default one)
     * @param script  Gradle init script
     * @return command line arguments
     */
    private static List<String> createArguments(Path wrapper, Path script) {
        List<String> ret = new LinkedList<>();
        ret.add("-I");
        ret.add(script.toString());
        // disabling parallel builds
        ret.add("-Dorg.gradle.parallel=false");
        // turning off Gradle version check
        // see https://discuss.gradle.org/t/gradle-thinks-2-10-is-less-than-2-2-when-resolving-plugins/13434/3
        // it blocks indexing of github.com/facebook/react-native for example
        ret.add("-Dcom.android.build.gradle.overrideVersionCheck=true");
        ret.add("srclibCollectMetaInformation");

        if (SystemUtils.IS_OS_WINDOWS) {
            if (wrapper == null) {
                ret.add(0, GRADLE_CMD_WINDOWS);
            } else {
                ret.add(0, wrapper.toAbsolutePath().toString());
            }
        } else {
            if (wrapper == null) {
                ret.add(0, GRADLE_CMD_OTHER);
            } else {
                ret.add(0, wrapper.toAbsolutePath().toString());
                ret.add(0, "bash");
            }
        }
        return ret;
    }

    /**
     * Collects project and its dependencies
     *
     * @param id    project identifier
     * @param cache projects cache
     * @return list of projects that includes self and all dependencies
     */
    private static Collection<Project> collectProjects(String id, Map<String, Project> cache) {
        Set<String> visited = new HashSet<>();
        Collection<Project> infos = new LinkedHashSet<>();
        collectProjects(id, infos, cache, visited);
        return infos;
    }

    /**
     * Collects project and its dependencies
     *
     * @param id      project identiier
     * @param infos   collection to fill with found information
     * @param cache   projects cache
     * @param visited set to control visited source units to avoid infinite loops
     */
    private static void collectProjects(String id,
                                        Collection<Project> infos,
                                        Map<String, Project> cache,
                                        Set<String> visited) {
        visited.add(id);
        Project project = cache.get(id);
        if (project == null) {
            return;
        }
        infos.add(project);

        for (ProjectDependency projectDependency : project.projectDependencies) {
            String depId = projectDependency.groupId + '/' + projectDependency.artifactId;
            if (!visited.contains(depId)) {
                collectProjects(depId, infos, cache, visited);
            }
        }
        // alexsaveliev: github.com/elastic/elasticsearch declares project dependencies as external
        // in order to resolve it, we'll check if there is an source unit with the same name
        // as declared in external dependency and use unit's sourcepath if found
        for (Dependency raw : project.dependencies) {
            String depId = raw.groupID + '/' + raw.artifactID;
            project = cache.get(depId);
            if (project != null) {
                if (!visited.contains(id)) {
                    collectProjects(id, infos, cache, visited);
                }
            }
        }
    }

    private static class Project {
        String artifactId;
        String groupId;
        String projectDir;
        Collection<String> classPath = new LinkedList<>();
        Collection<ProjectDependency> projectDependencies = new LinkedList<>();
        Collection<Dependency> dependencies = new LinkedList<>();
        Collection<SourcePathElement> sourceDirs = new LinkedList<>();
        String outputDir;

        String id() {
            return groupId + '/' + artifactId;
        }
    }

    /**
     * Project's dependency (reference to another sub-project or module that produces artifact A in group G by
     * build file B)
     */
    private static class ProjectDependency {
        String groupId;
        String artifactId;
        String buildFile;

        /**
         * Constructs new project dependency
         *
         * @param groupId    group ID
         * @param artifactId artifact ID
         * @param buildFile  sub-project's or module's build file used to build artifact, may ne null
         */
        ProjectDependency(String groupId, String artifactId, String buildFile) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            if (buildFile == null) {
                buildFile = StringUtils.EMPTY;
            }
            this.buildFile = buildFile;
        }
    }

    private static class SourcePathElement {

        String name;
        String filePath;

        /**
         * Constructs source path element
         *
         * @param name     source unit name
         * @param filePath path where source files for a given source unit may be found
         */
        SourcePathElement(String name, String filePath) {
            this.name = name;
            this.filePath = filePath;
        }

    }

    private static class Dependency {

        String groupID;
        String artifactID;
        String version;
        String scope;
        String file;

        Dependency(String groupID, String artifactID, String version, String scope, String file) {
            this.groupID = groupID;
            this.artifactID = artifactID;
            this.version = version;
            this.scope = scope;
            this.file = file;
        }
    }

}