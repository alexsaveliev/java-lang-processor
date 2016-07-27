package com.sourcegraph.langp;

import com.google.common.collect.Iterators;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.ArtifactDescriptorUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;

import java.util.*;

/**
 * Resolves Maven models
 */
@SuppressWarnings("deprecation")
class MavenModelResolver implements ModelResolver {

    private static final String POM = "pom";

    private Set<String> repositoryKeys;
    private List<RemoteRepository> repositories;

    private RepositorySystem repositorySystem;
    private RepositorySystemSession repositorySystemSession;

    private RemoteRepositoryManager remoteRepositoryManager;

    MavenModelResolver(RemoteRepositoryManager remoteRepositoryManager,
                       RepositorySystem repositorySystem,
                       RepositorySystemSession repositorySystemSession) {
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = repositorySystemSession;
        this.remoteRepositoryManager = remoteRepositoryManager;
        repositoryKeys = new HashSet<>();
        repositories = new LinkedList<>();

        RemoteRepository central = new RemoteRepository.Builder("central",
                "default",
                "http://central.maven.org/maven2/").build();
        repositoryKeys.add(central.getId());
        repositories.add(central);
    }

    private MavenModelResolver(MavenModelResolver source) {
        this.repositorySystem = source.repositorySystem;
        this.repositorySystemSession = source.repositorySystemSession;
        this.remoteRepositoryManager = source.remoteRepositoryManager;
        this.repositories = new LinkedList<>(source.repositories);
        this.repositoryKeys = new HashSet<>(source.repositoryKeys);
    }


    @Override
    public org.apache.maven.model.building.ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {

        Artifact pomArtifact = new DefaultArtifact(groupId, artifactId, StringUtils.EMPTY, POM, version);

        try {
            ArtifactRequest request = new ArtifactRequest(pomArtifact, repositories, null);
            pomArtifact = repositorySystem.resolveArtifact(repositorySystemSession, request).getArtifact();
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
        return new FileModelSource(pomArtifact.getFile());
    }

    @Override
    public org.apache.maven.model.building.ModelSource resolveModel(Parent parent) throws UnresolvableModelException {

        Artifact artifact = new DefaultArtifact(parent.getGroupId(),
                parent.getArtifactId(),
                StringUtils.EMPTY,
                POM,
                parent.getVersion());

        VersionRangeRequest versionRangeRequest = new VersionRangeRequest(artifact, repositories, null);

        try {
            VersionRangeResult versionRangeResult = repositorySystem.resolveVersionRange(repositorySystemSession,
                    versionRangeRequest);
            parent.setVersion(versionRangeResult.getHighestVersion().toString());
        } catch (VersionRangeResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), parent.getGroupId(), parent.getArtifactId(),
                    parent.getVersion(), e);

        }

        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        addRepository(repository, false);
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {

        String id = repository.getId();

        if (repositoryKeys.contains(id)) {
            if (!replace) {
                return;
            }
            Iterators.removeIf(repositories.iterator(), input -> input.getId().equals(id));
        }
        List<RemoteRepository> additions = Collections.singletonList(
                ArtifactDescriptorUtils.toRemoteRepository(repository));
        repositories =
                remoteRepositoryManager.aggregateRepositories(repositorySystemSession,
                        repositories,
                        additions,
                        true);
        repositoryKeys.add(id);
    }

    @Override
    public ModelResolver newCopy() {
        return new MavenModelResolver(this);
    }
}
