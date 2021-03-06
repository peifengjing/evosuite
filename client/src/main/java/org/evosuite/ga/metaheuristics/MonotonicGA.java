/**
 * Copyright (C) 2010-2017 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics;

import com.sun.javafx.util.Logging;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import java.util.stream.Collector;
import java.util.stream.Collectors;
import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.FitnessReplacementFunction;
import org.evosuite.ga.ReplacementFunction;
import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteSerialization;
import org.evosuite.utils.DebuggingObjectOutputStream;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of steady state GA
 * 
 * @author Gordon Fraser
 */
public class MonotonicGA<T extends Chromosome> extends GeneticAlgorithm<T> {

	private static final long serialVersionUID = 7846967347821123201L;

	protected ReplacementFunction replacementFunction;

	private final Logger logger = LoggerFactory.getLogger(MonotonicGA.class);

	/* UIUC CS527 export path */
	String targetFolder = ".evosuite-custom/";
	String targetPrefix = "population-";
	String targetSuffix = ".txt";

	/**
	 * Constructor
	 * 
	 * @param factory
	 *            a {@link org.evosuite.ga.ChromosomeFactory} object.
	 */
	public MonotonicGA(ChromosomeFactory<T> factory) {
		super(factory);

		setReplacementFunction(new FitnessReplacementFunction());
	}

	/**
	 * <p>
	 * keepOffspring
	 * </p>
	 * 
	 * @param parent1
	 *            a {@link org.evosuite.ga.Chromosome} object.
	 * @param parent2
	 *            a {@link org.evosuite.ga.Chromosome} object.
	 * @param offspring1
	 *            a {@link org.evosuite.ga.Chromosome} object.
	 * @param offspring2
	 *            a {@link org.evosuite.ga.Chromosome} object.
	 * @return a boolean.
	 */
	protected boolean keepOffspring(Chromosome parent1, Chromosome parent2, Chromosome offspring1,
			Chromosome offspring2) {
		return replacementFunction.keepOffspring(parent1, parent2, offspring1, offspring2);
	}

	/** {@inheritDoc} */
	@SuppressWarnings("unchecked")
	@Override
	protected void evolve() {
		List<T> newGeneration = new ArrayList<T>();

		// Elitism
		logger.debug("Elitism");
		newGeneration.addAll(elitism());

		// Add random elements
		// new_generation.addAll(randomism());

		while (!isNextPopulationFull(newGeneration) && !isFinished()) {
			logger.debug("Generating offspring");

			T parent1 = selectionFunction.select(population);
			T parent2;
			if (Properties.HEADLESS_CHICKEN_TEST) {
				parent2 = newRandomIndividual(); // crossover with new random
				// individual
			}
			else {
				parent2 = selectionFunction.select(population); // crossover
				// with existing
				// individual
			}

			T offspring1 = (T) parent1.clone();
			T offspring2 = (T) parent2.clone();
			try {
				// Crossover
				if (Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
					crossoverFunction.crossOver(offspring1, offspring2);
				}

			} catch (ConstructionFailedException e) {
				logger.info("CrossOver failed");
				continue;
			}

			// Mutation
			notifyMutation(offspring1);
			offspring1.mutate();
			notifyMutation(offspring2);
			offspring2.mutate();

			if (offspring1.isChanged()) {
				offspring1.updateAge(currentIteration);
			}
			if (offspring2.isChanged()) {
				offspring2.updateAge(currentIteration);
			}

			// The two offspring replace the parents if and only if one of
			// the offspring is not worse than the best parent.
			for (FitnessFunction<T> fitnessFunction : fitnessFunctions) {
				fitnessFunction.getFitness(offspring1);
				notifyEvaluation(offspring1);
				fitnessFunction.getFitness(offspring2);
				notifyEvaluation(offspring2);
			}

			if (keepOffspring(parent1, parent2, offspring1, offspring2)) {
				logger.debug("Keeping offspring");

				// Reject offspring straight away if it's too long
				int rejected = 0;
				if (isTooLong(offspring1) || offspring1.size() == 0) {
					rejected++;
				} else {
					// if(Properties.ADAPTIVE_LOCAL_SEARCH ==
					// AdaptiveLocalSearchTarget.ALL)
					// applyAdaptiveLocalSearch(offspring1);
					newGeneration.add(offspring1);
				}

				if (isTooLong(offspring2) || offspring2.size() == 0) {
					rejected++;
				} else {
					// if(Properties.ADAPTIVE_LOCAL_SEARCH ==
					// AdaptiveLocalSearchTarget.ALL)
					// applyAdaptiveLocalSearch(offspring2);
					newGeneration.add(offspring2);
				}

				if (rejected == 1) {
					newGeneration.add(Randomness.choice(parent1, parent2));
				} else if (rejected == 2) {
					newGeneration.add(parent1);
					newGeneration.add(parent2);
				}
			} else {
				logger.debug("Keeping parents");
				newGeneration.add(parent1);
				newGeneration.add(parent2);
			}

		}

		population = newGeneration;
		// archive
		updateFitnessFunctionsAndValues();

		currentIteration++;
	}

	private T newRandomIndividual() {
		T randomChromosome = chromosomeFactory.getChromosome();
		for (FitnessFunction<?> fitnessFunction : this.fitnessFunctions) {
			randomChromosome.addFitness(fitnessFunction);
		}
		return randomChromosome;
	}

	/** {@inheritDoc} */
	@Override
	public void initializePopulation() {
		notifySearchStarted();
		currentIteration = 0;

		// Set up initial population
		String targetFile = Properties.TARGET_CLASS;
		File target = new File(targetFolder + targetPrefix + targetFile + targetSuffix);
		if (!target.exists()) {
			generateInitialPopulation(Properties.POPULATION);
		} else {
			readPopulationFromFile(target);
			if (population.isEmpty()) {
				generateInitialPopulation(Properties.POPULATION);
			}
		}
		logger.debug("Calculating fitness of initial population");
		calculateFitnessAndSortPopulation();

		this.notifyIteration();
	}

	private static final double DELTA = 0.000000001; // it seems there is some
														// rounding error in LS,
														// but hard to debug :(

	/** {@inheritDoc} */
	@Override
	public void generateSolution() {
		if (Properties.ENABLE_SECONDARY_OBJECTIVE_AFTER > 0 || Properties.ENABLE_SECONDARY_OBJECTIVE_STARVATION) {
			disableFirstSecondaryCriterion();
		}

		if (population.isEmpty()) {
			initializePopulation();
			assert!population.isEmpty() : "Could not create any test";
		}

		logger.debug("Starting evolution");
		Instant startTime = Instant.now();
		LoggingUtils.getEvoLogger().info("\nStart Time: " + startTime.toString());

		int starvationCounter = 0;
		double bestFitness = Double.MAX_VALUE;
		double lastBestFitness = Double.MAX_VALUE;
		if (getFitnessFunction().isMaximizationFunction()) {
			bestFitness = 0.0;
			lastBestFitness = 0.0;
		}
		// UIUC CS527, save last population in case the last iteration will terminate without reach
		// the best population
		List<T> lastPopulation = new ArrayList<>();

		while (!isFinished()) {

			// related to Properties.ENABLE_SECONDARY_OBJECTIVE_AFTER;
			// check the budget progress and activate a secondary criterion
			// according to the property value.

			{
				double bestFitnessBeforeEvolution = getBestFitness();
				evolve();
				sortPopulation();
				double bestFitnessAfterEvolution = getBestFitness();

				if (getFitnessFunction().isMaximizationFunction()) {
					assert (bestFitnessAfterEvolution >= (bestFitnessBeforeEvolution
							- DELTA)) :
							"best fitness before evolve()/sortPopulation() was: " + bestFitnessBeforeEvolution
									+ ", now best fitness is " + bestFitnessAfterEvolution;
				} else {
					assert (bestFitnessAfterEvolution <= (bestFitnessBeforeEvolution
							+ DELTA)) :
							"best fitness before evolve()/sortPopulation() was: " + bestFitnessBeforeEvolution
									+ ", now best fitness is " + bestFitnessAfterEvolution;
				}
			}

			{
				double bestFitnessBeforeLocalSearch = getBestFitness();
				applyLocalSearch();
				double bestFitnessAfterLocalSearch = getBestFitness();

				if (getFitnessFunction().isMaximizationFunction()) {
					assert (bestFitnessAfterLocalSearch >= (bestFitnessBeforeLocalSearch
							- DELTA)) :
							"best fitness before applyLocalSearch() was: " + bestFitnessBeforeLocalSearch
									+ ", now best fitness is " + bestFitnessAfterLocalSearch;
				} else {
					assert (bestFitnessAfterLocalSearch <= (bestFitnessBeforeLocalSearch
							+ DELTA)) :
							"best fitness before applyLocalSearch() was: " + bestFitnessBeforeLocalSearch
									+ ", now best fitness is " + bestFitnessAfterLocalSearch;
				}
			}

			/*
			 * TODO: before explanation: due to static state handling, LS can
			 * worse individuals. so, need to re-sort.
			 * 
			 * now: the system tests that were failing have no static state...
			 * so re-sorting does just hide the problem away, and reduce
			 * performance (likely significantly). it is definitively a bug
			 * somewhere...
			 */
			// sortPopulation();

			double newFitness = getBestFitness();

			if (getFitnessFunction().isMaximizationFunction()) {
				assert (newFitness >= (bestFitness - DELTA)) : "best fitness was: " + bestFitness
						+ ", now best fitness is " + newFitness;
			} else {
				assert (newFitness <= (bestFitness + DELTA)) : "best fitness was: " + bestFitness
						+ ", now best fitness is " + newFitness;
			}
			bestFitness = newFitness;

			if (Double.compare(bestFitness, lastBestFitness) == 0) {
				starvationCounter++;
			} else {
				logger.info("reset starvationCounter after " + starvationCounter + " iterations");
				starvationCounter = 0;
				lastBestFitness = bestFitness;
			}

			updateSecondaryCriterion(starvationCounter);

			logger.info("Current iteration: " + currentIteration);
			this.notifyIteration();

			logger.info("Population size: " + population.size());
			logger.info("Best individual has fitness: " + population.get(0).getFitness());
			logger.info("Worst individual has fitness: " + population.get(population.size() - 1).getFitness());

			/* UIUC CS527 Autumn 2017 Peifeng: Export Elapsed Time */
			Duration duration = Duration.between(startTime, Instant.now());
			Long durationInSeconds = duration.getSeconds();
			double coverage = getBestIndividual().getCoverage() * 100;
			LoggingUtils.getEvoLogger().info(String.format("[CS527 Peifeng] [Coverage: %.2f%%] [Elapsed "
							+ "Time: %d:%02d:%02d | %d mS]",
					coverage, durationInSeconds / 3600, (durationInSeconds % 3600) / 60, durationInSeconds % 60,
					duration.toMillis()));

			// UIUC CS527, save current population in case the final population will be terminated
			// before reach the best population
			if (!isFinished()) {
				lastPopulation = new ArrayList<>(population);
			}
		}

		// UIUC CS527: write to target file
		File parent = new File(targetFolder);
		if (!parent.exists()) {
			parent.mkdirs();
		}
		String targetFile = Properties.TARGET_CLASS;
		File target = new File(targetFolder + targetPrefix + targetFile + targetSuffix);
		writePopulationToFile(target, lastPopulation);
		/** read population to double check write and read function
		List<T> readPopulation = new ArrayList<>(population);
		readPopulationFromFile(target);
		LoggingUtils.getEvoLogger().info("list length are same? " + String.valueOf(readPopulation
				.size() == population.size()));
		LoggingUtils.getEvoLogger().info("list element are same class? " + String.valueOf
				(readPopulation.get(0).getClass().equals(population.get(0).getClass())));
		LoggingUtils.getEvoLogger().info("list element class is " + readPopulation.get(0).getClass());
		*/

		// archive
		TimeController.execute(this::updateBestIndividualFromArchive, "update from archive", 5_000);

		notifySearchFinished();
	}

	private double getBestFitness() {
		T bestIndividual = getBestIndividual();
		for (FitnessFunction<T> ff : fitnessFunctions) {
			ff.getFitness(bestIndividual);
		}
		return bestIndividual.getFitness();
	}

	/**
	 * <p>
	 * setReplacementFunction
	 * </p>
	 * 
	 * @param replacement_function
	 *            a {@link org.evosuite.ga.ReplacementFunction} object.
	 */
	public void setReplacementFunction(ReplacementFunction replacement_function) {
		this.replacementFunction = replacement_function;
	}

	/**
	 * <p>
	 * getReplacementFunction
	 * </p>
	 * 
	 * @return a {@link org.evosuite.ga.ReplacementFunction} object.
	 */
	public ReplacementFunction getReplacementFunction() {
		return replacementFunction;
	}


	/* UIUC CS527 Autumn 2017 Peifeng: Export List<T> population to File */
	// Export population right after evolution
	public void writePopulationToFile(File target, List<T> populationToWrite) {
		LoggingUtils.getEvoLogger().info(String.format("[CS527 Peifeng] Saving best population to %s,"
						+ " population size: %d", target.getAbsolutePath(), populationToWrite.size()));
		showCoverage(populationToWrite);
		try {
			ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(target));
			out.writeObject(populationToWrite);
			out.flush();
			out.close();
		} catch (IOException e) {
			LoggingUtils.getEvoLogger().error("[CS527 Peifeng] Failed to open/handle " + target
					.getAbsolutePath() + " " + "for " + "writing: " + e.getMessage());
		}
	}

	// UIUC CS527 Test Read Object
	public void readPopulationFromFile(File target) {
		if (!target.exists()) {
			LoggingUtils.getEvoLogger().info("[CS527 Peifeng] targetFile does not exist to read");
		} else {
			LoggingUtils.getEvoLogger().info("[CS527 Peifeng] Starting to read population file");
			try {
				ObjectInputStream in = new ObjectInputStream(new FileInputStream(target));
				Object readPopulation = in.readObject();

				// mix random population and read population to improve performance
				/*population = (List<T>) readPopulation;*/
				/* showCoverage(population); */
				generateInitialPopulation(Properties.POPULATION);
				population.addAll((List<T>) readPopulation);

			} catch (IOException e) {
				LoggingUtils.getEvoLogger().error("[CS527 Peifeng] IOException: Failed to open/handle " +
						target.getAbsolutePath() + " " + "for writing: " + e.getMessage());
			} catch (ClassNotFoundException e) {
				LoggingUtils.getEvoLogger().error("[CS527 Peifeng] ClassNotFoundException: Failed to "
						+ "open/handle " + target.getAbsolutePath() + " " + "for writing: " + e.getMessage());
			}
		}
	}

	// UIUC CS527 get coverage in the population
	public void showCoverage(List<T> showPopulation) {
		String output = showPopulation.stream()
				.map(item -> String.format("%.2f%%", item.getCoverage()*100))
				.collect(Collectors.joining(","));
		LoggingUtils.getEvoLogger().info("[CS527 Peifeng] Coverage of the population: " + output);
	}
}
