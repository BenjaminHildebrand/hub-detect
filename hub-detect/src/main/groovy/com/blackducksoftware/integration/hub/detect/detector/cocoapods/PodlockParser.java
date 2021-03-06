/**
 * hub-detect
 *
 * Copyright (C) 2019 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.blackducksoftware.integration.hub.detect.detector.cocoapods;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.synopsys.integration.bdio.graph.DependencyGraph;
import com.synopsys.integration.bdio.graph.builder.LazyExternalIdDependencyGraphBuilder;
import com.synopsys.integration.bdio.model.Forge;
import com.synopsys.integration.bdio.model.dependencyid.DependencyId;
import com.synopsys.integration.bdio.model.dependencyid.NameDependencyId;
import com.synopsys.integration.bdio.model.externalid.ExternalId;
import com.synopsys.integration.bdio.model.externalid.ExternalIdFactory;

public class PodlockParser {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    final static List<String> fuzzyVersionIdentifiers = new ArrayList<>(Arrays.asList(">", "<", "~>", "="));

    private final ExternalIdFactory externalIdFactory;

    public PodlockParser(final ExternalIdFactory externalIdFactory) {
        this.externalIdFactory = externalIdFactory;
    }

    public DependencyGraph extractDependencyGraph(final String podLockText) throws IOException {
        final LazyExternalIdDependencyGraphBuilder lazyBuilder = new LazyExternalIdDependencyGraphBuilder();
        final YAMLMapper mapper = new YAMLMapper();
        final PodfileLock podfileLock = mapper.readValue(podLockText, PodfileLock.class);

        final Map<DependencyId, Forge> forgeOverrides = createForgeOverrideMap(podfileLock);

        for (final Pod pod : podfileLock.pods) {
            logger.trace(String.format("Processing pod %s", pod.name));
            processPod(pod, forgeOverrides, lazyBuilder);
        }

        for (final Pod dependency : podfileLock.dependencies) {
            logger.trace(String.format("Processing pod dependency from pod lock file %s", dependency.name));
            final String podText = dependency.name;
            final Optional<DependencyId> dependencyId = parseDependencyId(podText);
            if (dependencyId.isPresent()) {
                lazyBuilder.addChildToRoot(dependencyId.get());
            }
        }
        logger.trace("Attempting to build the dependency graph.");
        final DependencyGraph dependencyGraph = lazyBuilder.build();
        logger.trace("Completed the dependency graph.");
        return dependencyGraph;
    }

    /*
     * Create an override map because GitHub has better KB support so we should override COCOAPODS forge when we know where it is from.
     */
    private Map<DependencyId, Forge> createForgeOverrideMap(final PodfileLock podfileLock) {
        final Map<DependencyId, Forge> forgeOverrideMap = new HashMap<>();
        if (null != podfileLock.externalSources) {
            final List<PodSource> podSources = podfileLock.externalSources.sources;
            for (final PodSource podSource : podSources) {
                final Optional<DependencyId> dependencyId = parseDependencyId(podSource.name);
                if (dependencyId.isPresent()) {
                    if (null != podSource.git && podSource.git.contains("github")) {
                        forgeOverrideMap.put(dependencyId.get(), Forge.COCOAPODS);
                    } else if (null != podSource.path && podSource.path.contains("node_modules")) {
                        forgeOverrideMap.put(dependencyId.get(), Forge.NPM);
                    }
                }
            }
        }
        return forgeOverrideMap;
    }

    private Forge getForge(final DependencyId dependencyId, final Map<DependencyId, Forge> forgeOverrides) {
        if (forgeOverrides.containsKey(dependencyId)) {
            return forgeOverrides.get(dependencyId);
        }
        return Forge.COCOAPODS;
    }

    private void processPod(final Pod pod, final Map<DependencyId, Forge> forgeOverrides, final LazyExternalIdDependencyGraphBuilder lazyBuilder) {
        final String podText = pod.name;
        final Optional<DependencyId> dependencyIdMaybe = parseDependencyId(podText);
        final String name = parseCorrectPodName(podText).orElse(null);
        final String version = parseVersion(podText).orElse(null);
        if (dependencyIdMaybe.isPresent()) {
            final DependencyId dependencyId = dependencyIdMaybe.get();

            final Forge forge = getForge(dependencyId, forgeOverrides);
            final ExternalId externalId = externalIdFactory.createNameVersionExternalId(forge, name, version);

            lazyBuilder.setDependencyInfo(dependencyId, name, version, externalId);

            for (final String child : pod.dependencies) {
                logger.trace(String.format("Processing pod dependency %s", child));
                final Optional<DependencyId> childId = parseDependencyId(child);
                if (childId.isPresent()) {
                    if (!dependencyId.equals(childId.get())) {
                        lazyBuilder.addParentWithChild(dependencyId, childId.get());
                    }
                }
            }
        }
    }

    private Optional<String> parseCorrectPodName(final String podText) {
        // due to the way the KB deals with subspecs we should use the super name if it exists as this pod's name.
        final Optional<String> podName = parseRawPodName(podText);
        if (podName.isPresent()) {
            final Optional<String> superPodName = parseSuperPodName(podName.get());
            if (superPodName.isPresent()) {
                return superPodName;
            } else {
                return podName;
            }
        }
        return Optional.empty();
    }

    private Optional<String> parseSuperPodName(final String podName) {
        if (podName.contains("/")) {
            return Optional.of(podName.split("/")[0].trim());
        }
        return Optional.empty();
    }

    private Optional<DependencyId> parseDependencyId(final String podText) {
        final Optional<String> name = parseCorrectPodName(podText);
        if (name.isPresent()) {
            return Optional.of(new NameDependencyId(name.get()));
        }
        return Optional.empty();
    }

    private Optional<String> parseVersion(final String podText) {
        final String[] segments = podText.split(" ");
        if (segments.length > 1) {
            String version = segments[1];
            version = version.replace("(", "").replace(")", "").trim();
            if (!isVersionFuzzy(version)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }

    private boolean isVersionFuzzy(final String versionName) {
        for (final String identifier : fuzzyVersionIdentifiers) {
            if (versionName.contains(identifier)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> parseRawPodName(final String podText) {
        if (StringUtils.isNotBlank(podText)) {
            return Optional.of(podText.split(" ")[0].trim());
        }
        return Optional.empty();
    }

}
