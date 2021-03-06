/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.loading;

import com.carrotsearch.hppc.ObjectLongMap;
import org.neo4j.graphalgo.NodeLabel;
import org.neo4j.graphalgo.NodeProjections;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.PropertyMappings;
import org.neo4j.graphalgo.RelationshipProjections;
import org.neo4j.graphalgo.RelationshipType;
import org.neo4j.graphalgo.api.GraphLoadingContext;
import org.neo4j.graphalgo.api.GraphStoreFactory;
import org.neo4j.graphalgo.config.GraphCreateFromStoreConfig;
import org.neo4j.graphalgo.config.ImmutableGraphCreateFromStoreConfig;
import org.neo4j.graphalgo.core.GraphDimensions;
import org.neo4j.graphalgo.core.GraphDimensionsStoreReader;
import org.neo4j.graphalgo.core.huge.AdjacencyList;
import org.neo4j.graphalgo.core.huge.AdjacencyOffsets;
import org.neo4j.graphalgo.core.huge.HugeGraph;
import org.neo4j.graphalgo.core.utils.BatchingProgressLogger;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.mem.MemoryEstimation;
import org.neo4j.graphalgo.core.utils.mem.MemoryEstimations;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;

import java.util.Map;
import java.util.stream.Collectors;

import static org.neo4j.graphalgo.core.GraphDimensionsValidation.validate;

public final class NativeFactory extends GraphStoreFactory<GraphCreateFromStoreConfig> {

    private final GraphCreateFromStoreConfig storeConfig;

    public NativeFactory(
        GraphCreateFromStoreConfig graphCreateConfig,
        GraphLoadingContext loadingContext
    ) {
        super(graphCreateConfig, loadingContext, new GraphDimensionsStoreReader(loadingContext.api(), graphCreateConfig).call());
        this.storeConfig = graphCreateConfig;
    }

    @Override
    public MemoryEstimation memoryEstimation() {
        return memoryEstimation(dimensions);
    }

    @Override
    public MemoryEstimation memoryEstimation(GraphDimensions dimensions) {
        return getMemoryEstimation(dimensions, storeConfig);
    }

    public static MemoryEstimation getMemoryEstimation(GraphDimensions dimensions, RelationshipProjections relationshipProjections) {
        GraphCreateFromStoreConfig config = ImmutableGraphCreateFromStoreConfig
            .builder()
            .graphName("")
            .username("")
            .nodeProjections(NodeProjections.all())
            .relationshipProjections(relationshipProjections)
            .build();

        return getMemoryEstimation(dimensions, config);
    }

    public static MemoryEstimation getMemoryEstimation(GraphDimensions dimensions, GraphCreateFromStoreConfig config) {
        MemoryEstimations.Builder builder = MemoryEstimations
            .builder(HugeGraph.class)
            .add("nodeIdMap", IdMap.memoryEstimation());

        // nodes
        dimensions
            .nodePropertyTokens()
            .keySet()
            .forEach(property -> builder.add(property, NodePropertyMap.memoryEstimation()));

        // relationships
        config.relationshipProjections().projections().forEach((relationshipType, relationshipProjection) -> {

            boolean undirected = relationshipProjection.orientation() == Orientation.UNDIRECTED;

            // adjacency list
            builder.add(
                String.format("adjacency list for '%s'", relationshipType),
                AdjacencyList.compressedMemoryEstimation(relationshipType, undirected)
            );
            builder.add(
                String.format("adjacency offsets for '%s'", relationshipType),
                AdjacencyOffsets.memoryEstimation()
            );
            // all properties per projection
            relationshipProjection.properties().mappings().forEach(resolvedPropertyMapping -> {
                builder.add(
                    String.format("property '%s.%s", relationshipType, resolvedPropertyMapping.propertyKey()),
                    AdjacencyList.uncompressedMemoryEstimation(relationshipType, undirected)
                );
                builder.add(
                    String.format("property offset '%s.%s", relationshipType, resolvedPropertyMapping.propertyKey()),
                    AdjacencyOffsets.memoryEstimation()
                );
            });
        });

        return builder.build();
    }

    @Override
    protected ProgressLogger initProgressLogger() {
        long relationshipCount = graphCreateConfig
            .relationshipProjections()
            .projections()
            .entrySet()
            .stream()
            .map(entry -> {
                Long relCount = entry.getKey().name.equals("*")
                     ? dimensions.relationshipCounts().values().stream().reduce(Long::sum).orElse(0L)
                     : dimensions.relationshipCounts().getOrDefault(entry.getKey(), 0L);

                return entry.getValue().orientation() == Orientation.UNDIRECTED
                    ? relCount * 2
                    : relCount;
            }).mapToLong(Long::longValue).sum();

        return new BatchingProgressLogger(
            loadingContext.log(),
            dimensions.nodeCount() + relationshipCount,
            TASK_LOADING,
            graphCreateConfig.readConcurrency()
        );
    }

    @Override
    public ImportResult build() {
        validate(dimensions, storeConfig);

        int concurrency = graphCreateConfig.readConcurrency();
        AllocationTracker tracker = loadingContext.tracker();
        IdsAndProperties nodes = loadNodes(tracker, concurrency);
        RelationshipImportResult relationships = loadRelationships(tracker, nodes, concurrency);
        GraphStore graphStore = createGraphStore(nodes, relationships, tracker, dimensions);
        progressLogger.logMessage(tracker);

        return ImportResult.of(dimensions, graphStore);
    }

    private IdsAndProperties loadNodes(AllocationTracker tracker, int concurrency) {
        Map<NodeLabel, PropertyMappings> propertyMappingsByNodeLabel = graphCreateConfig
            .nodeProjections()
            .projections()
            .entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().properties()
            ));

        return new ScanningNodesImporter(
            graphCreateConfig,
            loadingContext.api(),
            dimensions,
            progressLogger,
            tracker,
            loadingContext.terminationFlag(),
            threadPool,
            concurrency,
            propertyMappingsByNodeLabel
        ).call(loadingContext.log());
    }

    private RelationshipImportResult loadRelationships(
        AllocationTracker tracker,
        IdsAndProperties idsAndProperties,
        int concurrency
    ) {
        Map<RelationshipType, RelationshipsBuilder> allBuilders = graphCreateConfig
            .relationshipProjections()
            .projections()
            .entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                projectionEntry -> new RelationshipsBuilder(projectionEntry.getValue(), tracker)
            ));

        ObjectLongMap<RelationshipType> relationshipCounts = new ScanningRelationshipsImporter(
            graphCreateConfig,
            loadingContext,
            loadingContext.api(),
            dimensions,
            progressLogger,
            tracker,
            idsAndProperties.idMap,
            allBuilders,
            threadPool,
            concurrency
        ).call(loadingContext.log());

        return RelationshipImportResult.of(allBuilders, relationshipCounts, dimensions);
    }
}
