package com.sourcegraph.langp;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.ArtifactDescriptorUtils;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.internal.impl.DefaultRemoteRepositoryManager;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

class MavenConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenConfiguration.class);

    /**
     * Model builder factory used to produce Maven projects
     */
    private static DefaultModelBuilderFactory modelBuilderFactory = new DefaultModelBuilderFactory();

    /**
     * Maven repository system
     */
    private static RepositorySystem repositorySystem;

    /**
     * Maven repository system session
     */
    private static RepositorySystemSession repositorySystemSession;

    static {
        // initializing repository system and session
        initRepositorySystem();
    }

    /**
     * Initializes Maven repository system and session
     */
    private static void initRepositorySystem() {
        repositorySystem = newRepositorySystem();
        repositorySystemSession = newRepositorySystemSession(repositorySystem);
    }

    /**
     * Initializes repository system
     *
     * @return repository system
     */
    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                LOGGER.error("Failed co create service {} using implementation {}", type, impl, exception);
            }
        });

        return locator.getService(RepositorySystem.class);
    }

    /**
     * Initializes repository system session
     *
     * @param system repository system to use
     * @return repository system session
     */
    private static RepositorySystemSession newRepositorySystemSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo =
                new LocalRepository(org.apache.maven.repository.RepositorySystem.defaultUserLocalRepository);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        return session;
    }

    static boolean prepare(Path path) {
        LOGGER.info("Scanning for Maven project descriptors in {}", path);
        Collection<Path> descriptors = getDescriptors(path);
        Map<String, MavenProject> idToProjectMap = new HashMap<>();
        Map<Path, MavenProject> pathToProjectMap = new HashMap<>();
        Collection<Repository> repositories = new HashSet<>();

        // makings maps of group/artifactid -> maven project and pom.xml -> maven project.
        // first one will be used to find sub-project dependencies,
        // the second one to associate pom.xml's directory with the javac config built
        for (Path descriptor : descriptors) {
            LOGGER.info("Parsing {}", descriptor);
            try {
                MavenProject project = getMavenProject(descriptor);
                idToProjectMap.put(project.getGroupId() + '/' + project.getArtifactId(), project);
                pathToProjectMap.put(descriptor.toAbsolutePath().normalize(), project);
                repositories.addAll(project.getRepositories());
            } catch (ModelBuildingException e) {
                LOGGER.warn("Cannot parse Maven project descriptor {}", path, e);
            }
        }


        for (Map.Entry<Path, MavenProject> entry : pathToProjectMap.entrySet()) {
            LOGGER.info("Processing {}", entry.getKey());
            MavenProject project = entry.getValue();
            LanguageServerConfiguration configuration = new LanguageServerConfiguration();
            configuration.sources = collectSourcePath(project, idToProjectMap);
            configuration.outputDirectory = project.getBuild().getOutputDirectory();
            // will fetch external dependencies only
            Collection<Dependency> externalDependencies = collectDependencies(project,
                    idToProjectMap,
                    pathToProjectMap).
                    stream().
                    filter(dep ->
                            !idToProjectMap.containsKey(dep.getGroupId() + '/' + dep.getArtifactId())).
                    collect(Collectors.toList());
            LOGGER.info("Fetching artifacts");
            Collection<Artifact> resolvedArtifacts = resolveDependencyArtifacts(externalDependencies,
                    repositories,
                    "jar");
            LOGGER.info("Fetched artifacts");
            List<String> classPath = new LinkedList<>();
            for (Artifact artifact : resolvedArtifacts) {
                File file = artifact.getFile();
                if (file != null) {
                    classPath.add(file.getAbsolutePath());
                }
            }
            configuration.classPath = classPath;
            try (FileWriter writer = new FileWriter(entry.getKey().getParent().resolve(".jls-config").toFile())) {
                JSONUtil.write(configuration, writer);
            } catch (IOException e) {
                LOGGER.warn("Failed to save configuration", e);
            }
        }

        return !pathToProjectMap.isEmpty();
    }

    /**
     * Collects source path from project and its local dependencies (local dependency is when Maven project A refers to
     * Maven project B from the same workspace)
     *
     * @param project        project to collect dependencies for
     * @param idToProjectMap map of group/artifactid -> maven project
     * @return list of source path from project and its local dependencies
     */
    private static Collection<String> collectSourcePath(MavenProject project, Map<String, MavenProject> idToProjectMap) {
        Collection<String> ret = new LinkedList<>();
        collectSourcePath(project, idToProjectMap, ret, new HashSet<>());
        return ret;
    }

    /**
     * Collects source path from project and its local dependencies (local dependency is when Maven project A refers to
     * Maven project B from the same workspace)
     *
     * @param project        project to collect dependencies for
     * @param idToProjectMap map of group/artifactid -> maven project
     * @param ret            target set
     * @param visited        tracks visited projects to avoid loops
     */
    private static void collectSourcePath(MavenProject project,
                                          Map<String, MavenProject> idToProjectMap,
                                          Collection<String> ret,
                                          Set<String> visited) {
        String id = project.getGroupId() + '/' + project.getArtifactId();
        if (!visited.add(id)) {
            return;
        }
        // extract project's source roots
        collectSourceRoots(project, ret);
        for (Dependency dependency : project.getDependencies()) {
            id = dependency.getGroupId() + '/' + dependency.getArtifactId();
            MavenProject dep = idToProjectMap.get(id);
            if (dep != null) {
                // extract project's local dependency source roots
                collectSourcePath(dep, idToProjectMap, ret, visited);
            }
        }
    }

    /**
     * Collects project's source roots
     *
     * @param project Maven project
     * @param ret     target collection to fill
     */
    private static void collectSourceRoots(MavenProject project, Collection<String> ret) {
        Path root = project.getModel().getPomFile().getParentFile().toPath();
        for (String sourceRoot : project.getCompileSourceRoots()) {
            File f = concat(root, sourceRoot).toFile();
            if (f.isDirectory()) {
                ret.add(f.toString());
            }
        }
        for (String sourceRoot : project.getTestCompileSourceRoots()) {
            File f = concat(root, sourceRoot).toFile();
            if (f.isDirectory()) {
                ret.add(f.toString());
            }
        }

        String sourceRoot = project.getBuild().getSourceDirectory();
        if (sourceRoot == null) {
            sourceRoot = "src/main";
        }
        File f = concat(root, sourceRoot).toFile();
        if (f.isDirectory()) {
            ret.add(f.toString());
        }

        sourceRoot = project.getBuild().getTestSourceDirectory();
        if (sourceRoot == null) {
            sourceRoot = "src/test";
        }
        f = concat(root, sourceRoot).toFile();
        if (f.isDirectory()) {
            ret.add(f.toString());
        }
    }

    /**
     * Concats (if needed) parent and child paths
     *
     * @param parent parent path
     * @param child  child path
     * @return concatenated path if child is relative or child if it's absolute
     */
    private static Path concat(Path parent, String child) {
        Path c = Paths.get(child);
        if (c.isAbsolute()) {
            return c;
        } else {
            return parent.resolve(c);
        }
    }

    /**
     * @param path workspace root
     * @return all pom.xml found
     */
    private static Collection<Path> getDescriptors(Path path) {
        Collection<Path> descriptors = new LinkedList<>();
        try {

            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (name.equals("pom.xml")) {
                        descriptors.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Unable to collect Maven project descriptors", e);
        }
        return descriptors;
    }

    /**
     * Parses Maven project
     *
     * @param descriptor pom.xml path
     * @return Maven project object
     * @throws ModelBuildingException
     */
    private static MavenProject getMavenProject(Path descriptor) throws ModelBuildingException {
        ModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setSystemProperties(System.getProperties());
        request.setPomFile(descriptor.toFile());
        request.setModelResolver(new MavenModelResolver(new DefaultRemoteRepositoryManager(),
                repositorySystem,
                repositorySystemSession));
        ModelBuildingResult result = modelBuilderFactory.newInstance().build(request);
        return new MavenProject(result.getEffectiveModel());
    }

    /**
     * Collects project's dependencies by including project direct dependencies and project module's direct dependencies
     *
     * @param project          project to collect dependencies for
     * @param idToProjectMap   map of group/artifactid -> maven project
     * @param pathToProjectMap map of pom.xml -> maven project
     * @return list of dependencies
     */
    private static Collection<Dependency> collectDependencies(MavenProject project,
                                                              Map<String, MavenProject> idToProjectMap,
                                                              Map<Path, MavenProject> pathToProjectMap) {
        Collection<Dependency> ret = new HashSet<>();
        collectDependencies(project, idToProjectMap, pathToProjectMap, ret, new HashSet<>());
        return ret;
    }

    /**
     * Collects project's dependencies by including project direct dependencies and project module's direct dependencies
     *
     * @param project          project to collect dependencies for
     * @param idToProjectMap   map of group/artifactid -> maven project
     * @param pathToProjectMap map of pom.xml -> maven project
     * @param ret              collection to fill
     * @param visited          tracks visited projects
     */
    private static void collectDependencies(MavenProject project,
                                            Map<String, MavenProject> idToProjectMap,
                                            Map<Path, MavenProject> pathToProjectMap,
                                            Collection<Dependency> ret,
                                            Set<String> visited) {
        String id = project.getGroupId() + '/' + project.getArtifactId();
        if (!visited.add(id)) {
            return;
        }

        ret.addAll(project.getDependencies());

        for (String module : project.getModules()) {
            Path modulePomFile = Paths.get(project.getModel().getPomFile().getParent(), module, "pom.xml").
                    toAbsolutePath().
                    normalize();
            MavenProject moduleProject = pathToProjectMap.get(modulePomFile);
            if (moduleProject != null) {
                collectDependencies(moduleProject, idToProjectMap, pathToProjectMap, ret, visited);
            }
        }
    }

    /**
     * Fetches dependency artifacts
     *
     * @param dependencies list of dependencies to fetch
     * @param repositories list of repositories to use
     * @param extension    artifact extension (jar, pom, ..)
     * @return list of artifacts fetched
     */
    private static Collection<Artifact> resolveDependencyArtifacts(Collection<Dependency> dependencies,
                                                                   Collection<Repository> repositories,
                                                                   String extension) {

        Collection<Artifact> ret = new LinkedList<>();

        List<org.eclipse.aether.graph.Dependency> deps = new LinkedList<>();
        ArtifactTypeRegistry artifactTypeRegistry = repositorySystemSession.getArtifactTypeRegistry();
        for (Dependency dependency : dependencies) {
            Artifact artifact = new DefaultArtifact(dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getClassifier(),
                    extension,
                    dependency.getVersion(),
                    artifactTypeRegistry.get(dependency.getType()));
            deps.add(new org.eclipse.aether.graph.Dependency(artifact, dependency.getScope()));
        }
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setDependencies(deps);
        List<RemoteRepository> repoz = repositories.stream().
                map(ArtifactDescriptorUtils::toRemoteRepository).collect(Collectors.toList());
        collectRequest.setRepositories(repoz);

        DependencyNode node;
        try {
            node = repositorySystem.collectDependencies(repositorySystemSession, collectRequest).getRoot();
        } catch (DependencyCollectionException e) {
            // TODO
            LOGGER.warn("Failed to collect dependencies - {}", e.getMessage(), e);
            node = e.getResult().getRoot();
        }
        LOGGER.debug("Collected dependencies");
        if (node == null) {
            LOGGER.warn("Failed to collect dependencies - no dependencies were collected");
            return ret;
        }

        DependencyRequest projectDependencyRequest = new DependencyRequest(node, null);
        try {
            repositorySystem.resolveDependencies(repositorySystemSession, projectDependencyRequest);
        } catch (DependencyResolutionException e) {
            LOGGER.warn("Failed to resolve dependencies - {}", e.getMessage());
        }

        PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
        node.accept(nlg);

        ret.addAll(nlg.getDependencies(true).stream().map(org.eclipse.aether.graph.Dependency::getArtifact).
                collect(Collectors.toList()));

        return ret;
    }

}