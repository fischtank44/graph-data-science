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
package org.neo4j.graphalgo.similarity;

import org.neo4j.graphalgo.core.CypherMapWrapper;
import org.neo4j.graphalgo.impl.results.SimilarityResult;
import org.neo4j.graphalgo.impl.results.SimilaritySummaryResult;
import org.neo4j.graphalgo.impl.similarity.modern.ModernPearsonAlgorithm;
import org.neo4j.graphalgo.impl.similarity.modern.ModernPearsonConfig;
import org.neo4j.graphalgo.impl.similarity.modern.ModernPearsonConfigImpl;
import org.neo4j.graphalgo.newapi.GraphCreateConfig;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.neo4j.procedure.Mode.READ;
import static org.neo4j.procedure.Mode.WRITE;

public final class ModernPearsonProc extends ModernSimilarityProc<ModernPearsonAlgorithm, ModernPearsonConfig> {

    @Procedure(name = "gds.alpha.similarity.pearson.stream", mode = READ)
    public Stream<SimilarityResult> pearsonStream(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return stream(graphNameOrConfig, configuration);
    }

    @Procedure(name = "gds.alpha.similarity.pearson.write", mode = WRITE)
    public Stream<SimilaritySummaryResult> pearsonWrite(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return write(graphNameOrConfig, configuration);
    }

    @Override
    protected ModernPearsonConfig newConfig(
        String username,
        Optional<String> graphName,
        Optional<GraphCreateConfig> maybeImplicitCreate,
        CypherMapWrapper userInput
    ) {
        return new ModernPearsonConfigImpl(graphName, maybeImplicitCreate, username, userInput);
    }

    @Override
    ModernPearsonAlgorithm newAlgo(ModernPearsonConfig config) {
        return new ModernPearsonAlgorithm(config, api);
    }
}
