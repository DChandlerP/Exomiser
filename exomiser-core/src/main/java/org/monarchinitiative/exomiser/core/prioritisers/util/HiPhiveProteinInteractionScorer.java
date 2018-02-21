/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2018 Queen Mary University of London.
 * Copyright (c) 2012-2016 Charité Universitätsmedizin Berlin and Genome Research Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.monarchinitiative.exomiser.core.prioritisers.util;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import org.jblas.FloatMatrix;
import org.monarchinitiative.exomiser.core.prioritisers.model.GeneMatch;
import org.monarchinitiative.exomiser.core.prioritisers.model.GeneModelPhenotypeMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public class HiPhiveProteinInteractionScorer {

    private static final Logger logger = LoggerFactory.getLogger(HiPhiveProteinInteractionScorer.class);

    public static final HiPhiveProteinInteractionScorer EMPTY = new HiPhiveProteinInteractionScorer();

    private final DataMatrix dataMatrix;

    private final ListMultimap<Integer, GeneModelPhenotypeMatch> bestGeneModels;
    private final double highQualityPhenoScoreCutOff;

    private final Map<Integer, Double> highQualityPhenoMatchedGeneScores;
    private final List<Integer> highQualityPhenoMatchedGeneIds;
    private final FloatMatrix weightedHighQualityMatrix;

    private HiPhiveProteinInteractionScorer() {
        this.dataMatrix = DataMatrix.EMPTY;

        //This isn't quite right... these:
        this.bestGeneModels = ArrayListMultimap.create();
        this.highQualityPhenoScoreCutOff = 0.0;
        //should be used to produce these wrapped in some matcher or some-such:
        highQualityPhenoMatchedGeneScores = Collections.emptyMap();
        highQualityPhenoMatchedGeneIds = Collections.emptyList();
        weightedHighQualityMatrix = FloatMatrix.EMPTY;
    }

    public HiPhiveProteinInteractionScorer(DataMatrix dataMatrix, ListMultimap<Integer, GeneModelPhenotypeMatch> bestGeneModels, double highQualityPhenoScoreCutOff) {
        this.dataMatrix = dataMatrix;
        this.bestGeneModels = bestGeneModels;
        this.highQualityPhenoScoreCutOff = highQualityPhenoScoreCutOff;

        highQualityPhenoMatchedGeneScores = getHighestGeneIdPhenoScores();
        highQualityPhenoMatchedGeneIds = Lists.newArrayList(highQualityPhenoMatchedGeneScores.keySet());
        weightedHighQualityMatrix = makeWeightedProteinInteractionMatrix();
    }

    private Map<Integer, Double> getHighestGeneIdPhenoScores() {
        Map<Integer, Double> highestGeneIdPhenoScores = new LinkedHashMap<>();
        for (GeneModelPhenotypeMatch geneModelPhenotypeMatch : bestGeneModels.values()) {
            Integer entrezId = geneModelPhenotypeMatch.getEntrezGeneId();
            Double score = geneModelPhenotypeMatch.getScore();
            // only build PPI network for high quality hits
            if (score > highQualityPhenoScoreCutOff) {
                logger.debug("Adding high quality score for {} score={}", geneModelPhenotypeMatch.getHumanGeneSymbol(), geneModelPhenotypeMatch
                        .getScore());
                if (!highestGeneIdPhenoScores.containsKey(entrezId) || score > highestGeneIdPhenoScores.get(entrezId)) {
                    highestGeneIdPhenoScores.put(entrezId, score);
                }
            }
        }
        logger.info("Using {} high quality phenotypic gene match scores (score > {})", highestGeneIdPhenoScores.size(), highQualityPhenoScoreCutOff);
        return Collections.unmodifiableMap(highestGeneIdPhenoScores);
    }

    //If this returned a DataMatrix things might be a bit more convenient later on...
    private FloatMatrix makeWeightedProteinInteractionMatrix() {
        logger.info("Making weighted-score Protein-Protein interaction sub-matrix from high quality phenotypic gene matches...");
        logger.info("Original data matrix ({} rows * {} columns)", dataMatrix.getMatrix()
                .getRows(), dataMatrix.getMatrix().getColumns());
        int rows = dataMatrix.getMatrix().getRows();
        int cols = highQualityPhenoMatchedGeneScores.size();
        FloatMatrix highQualityPpiMatrix = FloatMatrix.zeros(rows, cols);
        int c = 0;
        for (Map.Entry<Integer, Double> entry : highQualityPhenoMatchedGeneScores.entrySet()) {
            Integer seedGeneEntrezId = entry.getKey();
            //The original DataMatrix is a symmetrical matrix this new one is asymmetrical with the original rows but only high-quality columns.
            if (dataMatrix.containsGene(seedGeneEntrezId)) {
                FloatMatrix column = dataMatrix.getColumnMatrixForGene(seedGeneEntrezId);
                // weight column by phenoScore
                //get the best model score for the gene from the highQualityPhenoMatchedGenes
                Double score = entry.getValue();
                column = column.mul(score.floatValue());
                highQualityPpiMatrix.putColumn(c, column);
            }
            c++;
        }
        logger.info("Made high quality interaction matrix ({} rows * {} columns)", highQualityPpiMatrix.getRows(), highQualityPpiMatrix
                .getColumns());
        return highQualityPpiMatrix;
    }

    public GeneMatch getClosestPhenoMatchInNetwork(Integer entrezGeneId) {
        if (!dataMatrix.containsGene(entrezGeneId) || highQualityPhenoMatchedGeneIds.isEmpty()) {
            return GeneMatch.NO_HIT;
        }
        int rowIndex = dataMatrix.getRowIndexForGene(entrezGeneId);
        int columnIndex = getColumnIndexOfMostPhenotypicallySimilarGene(rowIndex, entrezGeneId);

        /* Changed method to return -1 if no hit as otherwise could not distinguish between
        no hit or hit to 1st entry in column (entrezGene 50640). When querying with 50640 this
        resulted in a self-hit being returned with a PPI score of 0.5+0.7=1.2 and also lots of
        low-scoring (0.5) PPI hits to 50640 for other genes with no PPI match
         */
        if (columnIndex == -1) {
            return GeneMatch.NO_HIT;
        }

        // optimal adjustment based on benchmarking to allow walker scores to compete with low phenotype scores
        double walkerScore = 0.5 + weightedHighQualityMatrix.get(rowIndex, columnIndex);

        Integer closestGeneId = highQualityPhenoMatchedGeneIds.get(columnIndex);
        List<GeneModelPhenotypeMatch> models = bestGeneModels.get(closestGeneId);

        return GeneMatch.builder()
                .queryGeneId(entrezGeneId)
                .matchGeneId(closestGeneId)
                .score(walkerScore)
                .bestMatchModels(models)
                .build();
    }

    /**
     * This function retrieves the random walk similarity score for the gene
     *
     * @param entrezGeneId for which the random walk score is to be retrieved
     */
    // Can this can be done in a single operation without having to pre-compute the high-quality matrix?
    private int getColumnIndexOfMostPhenotypicallySimilarGene(int geneRowIndex, int entrezGeneId) {
        // Here were walking along all the columns of a row from the high quality matches,
        // i.e. traversing a list to find the value and position of the highest value in that list.
        // The matrix is (12511 rows * 303 columns)
        // The output of this function (assigned to columnIndex) is used to:
        // define the walkerScore:
        // walkerScore = 0.5 + weightedHighQualityMatrix.get(rowIndex, columnIndex);
        //
        // Integer closestGeneId = highQualityPhenoMatchedGeneIds.get(columnIndex);
        // closestPhysicallyInteractingGeneModels = bestGeneModels.get(closestGeneId);
        int bestHitIndex = -1;
        double bestScore = 0;
        for (int i = 0; i < highQualityPhenoMatchedGeneIds.size(); i++) {
            //slow auto-unboxing
            int geneId = highQualityPhenoMatchedGeneIds.get(i);
            //avoid self-hits now are testing genes with direct pheno-evidence as well
            if (geneId != entrezGeneId) {
                double cellScore = weightedHighQualityMatrix.get(geneRowIndex, i);
                bestScore = Math.max(bestScore, cellScore);
                if (cellScore == bestScore) {
                    bestHitIndex = i;
                }
            }
        }
        return bestHitIndex;
    }

    private int slowFindBestHitIndex(int geneRowIndex, int entrezGeneId) {
        int columnIndex = 0;
        double bestScore = 0;
        int bestHitIndex = -1;
        for (Integer similarGeneEntrezId : highQualityPhenoMatchedGeneIds) {
            if (!dataMatrix.containsGene(similarGeneEntrezId) || similarGeneEntrezId == entrezGeneId) {
                //avoid self-hits now are testing genes with direct pheno-evidence as well
                columnIndex++;
            } else {
                double cellScore = weightedHighQualityMatrix.get(geneRowIndex, columnIndex);
                if (cellScore > bestScore) {
                    bestScore = cellScore;
                    bestHitIndex = columnIndex;
                }
                columnIndex++;
            }
        }
        return bestHitIndex;
    }

}
