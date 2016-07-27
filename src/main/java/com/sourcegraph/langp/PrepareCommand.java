package com.sourcegraph.langp;

import com.beust.jcommander.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrepareCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrepareCommand.class);

    @Parameter(names = {"--workspace"}, description = "Workspace path", required = true)
    String workspace;

    /**
     * Main method
     */
    public void Execute() {

        Path path = Paths.get(workspace);
        try {
            if (MavenConfiguration.prepare(path)) {
                return;
            }
        } catch (Exception e) {
            LOGGER.error("Unexpected error occurred while collecting source units", e);
            System.exit(1);
        }
    }
}
