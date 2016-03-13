package chart_parser;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;

import cat_combination.FilledDependency;
import cat_combination.RuleInstancesParams;
import cat_combination.SuperCategory;
import cat_combination.Variable;
import io.Sentence;
import lexicon.Relations;
import model.Lexicon;
import uk.ac.cam.cl.depnn.NeuralNetwork;
import uk.ac.cam.cl.depnn.io.Dependency;
import uk.ac.cam.cl.depnn.io.Feature;
import utils.Pair;

public class ChartParserBeam extends ChartParser {
	private boolean cubePruning;
	private int beamSize;
	private double beta;

	private NeuralNetwork<Dependency> depnn;

	public ChartParserBeam(
					String grammarDir,
					boolean altMarkedup,
					boolean eisnerNormalForm,
					int MAX_WORDS,
					int MAX_SUPERCATS,
					RuleInstancesParams ruleInstancesParams,
					Lexicon lexicon,
					String featuresFile,
					String weightsFile,
					boolean newFeatures,
					boolean compactWeights,
					boolean cubePruning,
					int beamSize,
					double beta) throws IOException {
		super(grammarDir, altMarkedup, eisnerNormalForm, MAX_WORDS,
					MAX_SUPERCATS, false, false, ruleInstancesParams,
					lexicon, featuresFile, weightsFile, newFeatures, compactWeights);

		this.chart = new Chart(MAX_WORDS, categories.dependencyRelations, false, false);
		this.chart.setWeights(this.weights);

		this.cubePruning = cubePruning;
		this.beamSize = beamSize;
		this.beta = beta;
	}

	/*
	 * need the decoder to provide an IgnoreDepsEval object; this is breaking
	 * the separation between the two
	 */
	/**
	 * Parses one supertagged sentence using chart parser with beam search.
	 * 
	 * The method calls functions preParse() and postParse(), which can perform
	 * pre-parsing and post-parsing operations depending on the subclass e.g.
	 * ChartTrainParserBeam.
	 * 
	 * @param in file containing supertagged sentences to parse
	 * @param stagsIn file containing additional supertags
	 * @param log log file
	 * @param betas array of values of beta
	 * @return true if sentence is parsed or skipped, false if there are no
	 * sentences left
	 */
	@Override
	public boolean parseSentence(Sentence sentence,  double[] betas) throws IOException {
		if (betas.length != 1) {
			throw new IllegalArgumentException("Only need 1 beta value.");
		}

		this.sentence = sentence;

		maxWordsExceeded = false;
		int numWords = sentence.words.size();
		if ( numWords > chart.MAX_WORDS ) {
			logger.info(" Sentence has " + numWords + " words; MAX_WORDS exceeded.");
			maxWordsExceeded = true;
			return true;
		} else {
			logger.info(" Sentence has " + numWords + " words; chart capacity: " + (beamSize*numWords*(numWords+1)/2));
		}

		if (lexicon != null) {
			sentence.addIDs(lexicon);
		}

		maxSuperCatsExceeded = false;
		chart.clear();
		chart.load(sentence, betas[0], false, true);

		if (!preParse()) {
			return true;
		}

		/*
		 * apply unary rules to lexical categories; typeChange needs to come
		 * before typeRaise since some results of typeChange can be type-raised
		 * (but not vice versa)
		 */
		for (int i = 0; i < numWords; i++) {
			for (SuperCategory superCat : chart.cell(i, 1).getSuperCategories()) {
				calcScore(superCat, false);
			}

			typeChange(chart.cell(i, 1), i, 1);
			typeRaise(chart.cell(i, 1), i, 1);

			/*
			 * 260215: it was discovered that sorting the leaves (just sorting,
			 * no beam) can cause slight variations in the results; this has
			 * probably got to do with supercategories that have the same score,
			 * but only part of them survive the (later) beam.
			 * 
			 * By changing the order of supercategories in the leaves, the order
			 * of supercategories in the higher cells vary too, in the sense
			 * that supercategories having the same score can be in different
			 * order. This is because there is no further tiebreaker after
			 * comparing scores.
			 */
			chart.cell(i, 1).applyBeam(0, beta);

			postParse(i, 1, numWords);
		}

		jloop:
		for (int j = 2; j <= numWords; j++) {
			for (int i = 0; i <= numWords - j; i++) {
				setCellSize(chart, i, j);

				for (int k = 1; k < j; k++) {
					if (Chart.getNumSuperCategories() > MAX_SUPERCATS) {
						maxSuperCatsExceeded = true;
						logger.info("MAX_SUPERCATS exceeded. (" + Chart.getNumSuperCategories() + " > " + MAX_SUPERCATS + ")");
						break jloop;
					}

					logger.trace("Combining cells: (" + i + "," + k + ") (" + (i+k) + "," + (j-k) + ")");

					if (cubePruning) {
						combineBetter(chart.cell(i, k), chart.cell(i+k, j-k), i, j, (j == numWords));
					} else {
						combine(chart.cell(i, k), chart.cell(i+k, j-k), i, j, (j == numWords));
					}
				}

				if (cubePruning) {
					chart.cell(i, j).combinePreSuperCategories(beamSize);
				}

				if (j < numWords) {
					typeChange(chart.cell(i, j), i, j);
					typeRaise(chart.cell(i, j), i, j);
				}

				chart.cell(i, j).applyBeam(beamSize, beta);

				postParse(i, j, numWords);
			}
		}

		return true;
	}

	/**
	 * Dummy function for extensions to parseSentence() by subclasses.
	 * 
	 * @return true if pre-parsing succeeds, false if pre-parsing fails
	 * @throws IOException 
	 */
	protected boolean preParse() throws IOException {
		return true;
	}

	/**
	 * Dummy function for extensions to parseSentence() by subclasses.
	 * 
	 * @param pos position of the current cell in parseSentence()
	 * @param span span of the current cell in parseSentence()
	 * @param numWords number of words in sentence
	 */
	protected void postParse(int pos, int span, int numWords) {
		return;
	}

	/**
	 * Initialises initial cell capacity.
	 * 
	 * The initial (expected cell capacity) is the number of combine operations
	 * (span-1) multiplied by the number of possible combinations of supercats
	 * from two cells (beamSize * beamSize), and finally by 2 to allocate for
	 * extra supercats generated by type changing and raising.
	 * 
	 * @param chart chart
	 */
	private void setCellSize(Chart chart, int pos, int span) {
		int minCapacity = (span-1) * beamSize * beamSize * 2;
		chart.cell(pos, span).getSuperCategories().ensureCapacity(minCapacity);
	}

	public void combine(Cell leftCell, Cell rightCell, int position, int span, boolean atRoot) {
		results.clear();

		for (SuperCategory leftSuperCat : leftCell.getSuperCategories()) {
			for (SuperCategory rightSuperCat : rightCell.getSuperCategories()) {
				rules.combine(leftSuperCat, rightSuperCat, results, sentence);
			}
		}

		chart.addNoDP(position, span, results);

		for (SuperCategory superCat : results) {
			calcScore(superCat, atRoot);
		}
	}

	public void combineBetter(Cell leftCell, Cell rightCell, int position, int span, boolean atRoot) {
		if ( !leftCell.isEmpty() && !rightCell.isEmpty() ) {
			int leftSize = leftCell.getSuperCategories().size();
			int rightSize = rightCell.getSuperCategories().size();

			LinkedList<SuperCategory> kbest = new LinkedList<SuperCategory>();

			if ( leftSize*rightSize <= beamSize ) {
				results.clear();

				for (SuperCategory leftSuperCat : leftCell.getSuperCategories()) {
					for (SuperCategory rightSuperCat : rightCell.getSuperCategories()) {
						rules.combine(leftSuperCat, rightSuperCat, results, sentence);
					}
				}

				for (SuperCategory superCat : results) {
					calcScore(superCat, atRoot);
					kbest.add(superCat);
				}
			} else {
				LinkedList<Pair<Integer, Integer>> pairs = new LinkedList<Pair<Integer, Integer>>();
				PriorityQueue<Pair<SuperCategory, Pair<Integer, Integer>>> queue = 
						new PriorityQueue<Pair<SuperCategory, Pair<Integer, Integer>>>(beamSize,
								new Comparator<Pair<SuperCategory, Pair<Integer, Integer>>>(){
							@Override
							public int compare(Pair<SuperCategory, Pair<Integer, Integer>> p1, Pair<SuperCategory, Pair<Integer, Integer>> p2){
								if ( p1.x == null ) {
									return 0;
								} else if ( p2.x == null ) {
									return -1;
								} else {
									return p1.x.compareToScore(p2.x);
								}
							}});

				boolean[][] track = new boolean[leftSize][rightSize];

				int leftIndex = 0;
				int rightIndex = 0;

				pairs.add(new Pair<Integer, Integer>(leftIndex, rightIndex));
				track[leftIndex][rightIndex] = true;

				while (kbest.size() < beamSize) {
					while (!pairs.isEmpty()) {
						results.clear();

						Pair<Integer, Integer> pair = pairs.poll();
						leftIndex = pair.x;
						rightIndex = pair.y;

						SuperCategory leftSuperCat = leftCell.getSuperCategories().get(leftIndex);
						SuperCategory rightSuperCat = rightCell.getSuperCategories().get(rightIndex);

						rules.combine(leftSuperCat, rightSuperCat, results, sentence);

						if ( !results.isEmpty() ) {
							for (SuperCategory resultSuperCat : results) {
								calcScore(resultSuperCat, atRoot);
								Pair<SuperCategory, Pair<Integer, Integer>> queueElement = 
										new Pair<SuperCategory, Pair<Integer, Integer>>(resultSuperCat, new Pair<Integer, Integer>(leftIndex, rightIndex));
								queue.add(queueElement);
							}
						} else {
								queue.add(new Pair<SuperCategory, Pair<Integer, Integer>>(null, new Pair<Integer, Integer>(leftIndex, rightIndex)));
						}
					}

					Pair<SuperCategory, Pair<Integer, Integer>> topElement = queue.poll();

					if ( topElement == null ) {
						break;
					} else if ( topElement.x != null ) {
						kbest.add(topElement.x);
					}

					if ( topElement.y.x+1 < leftSize && !track[topElement.y.x+1][topElement.y.y] ) {
						pairs.add(new Pair<Integer, Integer>(topElement.y.x+1, topElement.y.y));
						track[topElement.y.x+1][topElement.y.y] = true;
					}

					if ( topElement.y.y+1 < rightSize && !track[topElement.y.x][topElement.y.y+1] ) {
						pairs.add(new Pair<Integer, Integer>(topElement.y.x, topElement.y.y+1));
						track[topElement.y.x][topElement.y.y+1] = true;
					}
				}
			}

			Collections.sort(kbest, SuperCategory.scoreComparator());
			chart.cell(position, span).getPreSuperCategories().add(kbest);
		}
	}

	@Override
	public void typeChange(Cell cell, int position, int span) {
		results.clear();
		rules.typeChange(cell.getSuperCategories(), results);
		chart.addNoDP(position, span, results);

		for (SuperCategory superCat : results) {
			calcScore(superCat, false);
		}
	}

	@Override
	public void typeRaise(Cell cell, int position, int span) {
		results.clear();
		rules.typeRaise(cell.getSuperCategories(), results);
		chart.addNoDP(position, span, results);

		for (SuperCategory superCat : results) {
			calcScore(superCat, false);
		}
	}

	public boolean root() {
		Cell root = chart.root();

		return !root.isEmpty();
	}

	/**
	 * Calculates the score of a supercategory.
	 * 
	 * The method assumes that the scores of its children (if any) have been
	 * calculated.
	 * 
	 * The method also assumes if the supercategory is a leaf, only its initial
	 * score has been calculated.
	 * 
	 * @param superCat supercategory
	 * @param atRoot if the supercategory is a root supercategory
	 */
	public void calcScore(SuperCategory superCat, boolean atRoot) {
		SuperCategory leftChild = superCat.leftChild;
		SuperCategory rightChild = superCat.rightChild;

		if (leftChild != null) {
			if (rightChild != null) {
				calcScoreBinary(superCat, leftChild.score + rightChild.score);
				if (atRoot) {
					calcScoreRoot(superCat);
				}
			} else {
				// assumes no unary rules applied at the root
				calcScoreUnary(superCat, leftChild.score);
			}
		} else {
			calcScoreLeaf(superCat);
		}

		if ( depnn != null ) {
			superCat.logDepNNScore = calcDepNNScore(superCat);
			superCat.score += weights.getDepNN() * superCat.logDepNNScore;
		}
	}

	/**
	 * Calculates the sum of initial scores of leaves of a tree.
	 * 
	 * @param superCat supercategory
	 * @return sum of initial scores of leaves
	 */
	public double calcSumLeafInitialScore(SuperCategory superCat) {
		SuperCategory leftChild = superCat.leftChild;
		SuperCategory rightChild = superCat.rightChild;

		double sum = 0.0;

		if (leftChild != null) {
			if (rightChild != null) {
				sum += calcSumLeafInitialScore(superCat.rightChild);
			}
			sum += calcSumLeafInitialScore(superCat.leftChild);
		} else {
			sum += superCat.logPScore;
		}

		return sum;
	}

	public double calcAverageSumDepNNScore(SuperCategory superCat) {
		Pair<Double, Integer> result = calcSumDepNNScore(superCat, 0);
		return result.x / result.y;
	}

	public Pair<Double, Integer> calcSumDepNNScore(SuperCategory superCat, int numCats) {
		SuperCategory leftChild = superCat.leftChild;
		SuperCategory rightChild = superCat.rightChild;

		double sum = superCat.logDepNNScore;
		numCats++;

		if (leftChild != null) {
			if (rightChild != null) {
				Pair<Double, Integer> resultRight = calcSumDepNNScore(superCat.rightChild, numCats);
				sum += resultRight.x;
				numCats += resultRight.y;
			}
			Pair<Double, Integer> resultLeft = calcSumDepNNScore(superCat.leftChild, numCats);
			sum += resultLeft.x;
			numCats += resultLeft.y;
		}

		return new Pair<Double, Integer>(sum, numCats);
	}

	/**
	 * Calculates the score of a root supercategory i.e. initial score + root
	 * feature scores.
	 * 
	 * The method assumes that the root supercategory already has an initial
	 * score from recursion.
	 * 
	 * @param superCat root supercategory
	 */
	private void calcScoreRoot(SuperCategory superCat) {
		featureIDs.clear();

		features.collectRootFeatures(superCat, sentence, featureIDs);

		for ( int featureID : featureIDs ) {
			superCat.score += weights.getWeight(featureID);
		}
	}

	/**
	 * Calculates the score of an unary supercategory i.e. child score + unary
	 * feature scores.
	 * 
	 * @param superCat unary supercategory
	 * @param childScore child score
	 */
	private void calcScoreUnary(SuperCategory superCat, double childScore) {
		superCat.score = childScore;
		featureIDs.clear();

		features.collectUnaryFeatures(superCat, sentence, featureIDs);

		for ( int featureID : featureIDs ) {
			superCat.score += weights.getWeight(featureID);
		}
	}

	/**
	 * Calculates the score of a binary supercategory i.e. children score +
	 * binary feature scores.
	 * 
	 * @param superCat binary supercategory
	 * @param childrenScore children score
	 */
	private void calcScoreBinary(SuperCategory superCat, double childrenScore) {
		superCat.score = childrenScore;
		featureIDs.clear();

		features.collectBinaryFeatures(superCat, sentence, featureIDs);

		for ( int featureID : featureIDs ) {
			superCat.score += weights.getWeight(featureID);
		}
	}

	/**
	 * Calculates the score of a leaf supercategory i.e. initial score + leaf
	 * feature scores.
	 * 
	 * The method assumes that the leaf supercategory already has an initial
	 * score from loading the sentence.
	 * 
	 * The method should only be called once for every leaf supercategory.
	 * 
	 * @param superCat leaf supercategory
	 */
	private void calcScoreLeaf(SuperCategory superCat) {
		featureIDs.clear();

		features.collectLeafFeatures(superCat, sentence, featureIDs);

		for ( int featureID : featureIDs ) {
			superCat.score += weights.getWeight(featureID);
		}
	}

	public double calcDepNNScore(SuperCategory superCat) {
		double score = 0.0;

		for ( FilledDependency dep :superCat.filledDeps ) {
			if ( !SuperCategory.ignoreDeps.ignoreDependency(dep, sentence) ) {
				String[] attributes = dep.getAttributes(categories.dependencyRelations, sentence);
				Dependency dependency= new Dependency();
				dependency.add(attributes[0]);
				dependency.add(attributes[1]);
				dependency.add(attributes[2]);
				dependency.add(attributes[3]);
				dependency.add(attributes[4]);
				dependency.add(attributes[5]);
				dependency.add(attributes[6]);
				score += Math.log(depnn.predictSoft(dependency));
			}
		}

		return score;
	}

	public void initDepNN(String modelDir) throws IOException {
		if ( depnn != null ) {
			depnn = new NeuralNetwork<Dependency>(modelDir, new Dependency());
		}
	}

	public void skimmer(PrintWriter out, Relations relations, Sentence sentence) {
		int numWords = sentence.words.size();

		skimmer(out, relations, sentence, 0, numWords);
	}

	private void skimmer(PrintWriter out, Relations relations, Sentence sentence, int pos, int span) {
		int maxPos = 0;
		int maxSpan = 0;

		SuperCategory maxCat = null;
		double maxScore = Double.NEGATIVE_INFINITY;

		jloop:
		for ( int j = span; j > 0; j-- ) {
			for ( int i = pos; i <= pos + span - j; i++ ) {
				Cell cell = chart.cell(i, j);
				if ( !cell.isEmpty() ) {
					for ( SuperCategory superCat : cell.getSuperCategories() ) {
						double currentScore = superCat.score;
						if ( currentScore > maxScore ) {
							maxScore = currentScore;
							maxCat = superCat;

							maxPos = i;
							maxSpan = j;
						}
					}
				}
			}

			if ( maxCat != null ) {
				break jloop;
			}
		}

		if ( maxCat == null ) {
			throw new Error("maxCat should not be null");
		}

		// left
		if ( maxPos > pos) {
			skimmer(out, relations, sentence, pos, maxPos - pos);
		}

		// centre
		printDeps(out, relations, sentence, maxCat);

		// right
		if ( pos + span > maxPos + maxSpan ) {
			skimmer(out, relations, sentence, maxPos + maxSpan, pos + span - maxPos - maxSpan);
		}
	}

	public void printDeps(PrintWriter out, Relations relations, Sentence sentence) {
		double maxScore = Double.NEGATIVE_INFINITY;
		SuperCategory maxRoot = null;

		Cell root = chart.root();

		for (SuperCategory superCat : root.getSuperCategories()) {
			double currentScore = superCat.score;
			if (currentScore > maxScore) {
				maxScore = currentScore;
				maxRoot = superCat;
			}
		}

		if (maxRoot != null) {
			printDeps(out, relations, sentence, maxRoot);
		}
	}

	public void printDeps(PrintWriter out, Relations relations, Sentence sentence, SuperCategory superCat) {
		for ( FilledDependency filled : superCat.filledDeps ) {
			filled.printFullJslot(out, relations, sentence);
		}

		if (superCat.leftChild != null) {
			printDeps(out, relations, sentence, superCat.leftChild);

			if (superCat.rightChild != null) {
				printDeps(out, relations, sentence, superCat.rightChild);
			}
		} else {
			sentence.addOutputSupertag(superCat.cat);
		}
	}

	public void printFeatures(PrintWriter outFeatures, Sentence sentence) {
		double maxScore = Double.NEGATIVE_INFINITY;
		SuperCategory maxRoot = null;

		Cell root = chart.root();

		for (SuperCategory superCat : root.getSuperCategories()) {
			double currentScore = superCat.score;
			if (currentScore > maxScore) {
				maxScore = currentScore;
				maxRoot = superCat;
			}
		}

		if (maxRoot != null) {
			printFeatures(outFeatures, sentence, maxRoot);
		}
	}

	public void printFeatures(PrintWriter outFeatures, Sentence sentence, SuperCategory superCat) {
		printFeature(outFeatures, sentence, superCat);

		if (superCat.leftChild != null) {
			printFeatures(outFeatures, sentence, superCat.leftChild);

			if (superCat.rightChild != null) {
				printFeatures(outFeatures, sentence, superCat.rightChild);
			}
		}
	}

	public void printFeature(PrintWriter outFeatures, Sentence sentence, SuperCategory superCat) {
		for ( ArrayList<String> feature : getFeature(sentence, superCat) ) {
			StringBuilder featureBuilder = new StringBuilder();

			for ( int i = 0; i < feature.size()-1; i++ ) {
				featureBuilder.append(feature.get(i));
				featureBuilder.append(" ");
			}

			featureBuilder.append(feature.get(feature.size()-1));
			outFeatures.println(featureBuilder.toString());
		}
	}

	public ArrayList<Feature> getFeature(Sentence sentence, SuperCategory superCat) {
		ArrayList<Feature> features = new ArrayList<Feature>();

		String topCat = superCat.cat.toString();
		String leftCat = "";
		String rightCat = "";
		String leftLeftCat = "";
		String leftRightCat = "";
		String rightLeftCat = "";
		String rightRightCat = "";

		ArrayList<Integer> topCatWords = new ArrayList<Integer>();
		ArrayList<Integer> leftCatWords = new ArrayList<Integer>();
		ArrayList<Integer> rightCatWords = new ArrayList<Integer>();
		ArrayList<Integer> leftLeftCatWords = new ArrayList<Integer>();
		ArrayList<Integer> leftRightCatWords = new ArrayList<Integer>();
		ArrayList<Integer> rightLeftCatWords = new ArrayList<Integer>();
		ArrayList<Integer> rightRightCatWords = new ArrayList<Integer>();

		ArrayList<Integer> topCatPoss = new ArrayList<Integer>();
		ArrayList<Integer> leftCatPoss = new ArrayList<Integer>();
		ArrayList<Integer> rightCatPoss = new ArrayList<Integer>();
		ArrayList<Integer> leftLeftCatPoss = new ArrayList<Integer>();
		ArrayList<Integer> leftRightCatPoss = new ArrayList<Integer>();
		ArrayList<Integer> rightLeftCatPoss = new ArrayList<Integer>();
		ArrayList<Integer> rightRightCatPoss = new ArrayList<Integer>();

		getWordPos(sentence, superCat, topCatWords, topCatPoss);

		if ( superCat.leftChild != null ) {
			leftCat = superCat.leftChild.cat.toString();
			getWordPos(sentence, superCat.leftChild, leftCatWords, leftCatPoss);

			if ( superCat.leftChild.leftChild != null ) {
				leftLeftCat = superCat.leftChild.leftChild.cat.toString();
				getWordPos(sentence, superCat.leftChild.leftChild, leftLeftCatWords, leftLeftCatPoss);
			}

			if ( superCat.leftChild.rightChild != null ) {
				leftRightCat = superCat.leftChild.rightChild.cat.toString();
				getWordPos(sentence, superCat.leftChild.rightChild, leftRightCatWords, leftRightCatPoss);
			}

			if ( superCat.rightChild != null ) {
				rightCat = superCat.rightChild.cat.toString();
				getWordPos(sentence, superCat.rightChild, rightCatWords, rightCatPoss);

				if ( superCat.rightChild.leftChild != null ) {
					rightLeftCat = superCat.rightChild.leftChild.cat.toString();
					getWordPos(sentence, superCat.rightChild.leftChild, rightLeftCatWords, rightLeftCatPoss);
				}

				if ( superCat.rightChild.rightChild != null ) {
					rightRightCat = superCat.rightChild.rightChild.cat.toString();
					getWordPos(sentence, superCat.rightChild.rightChild, rightRightCatWords, rightRightCatPoss);
				}
			}
		}

		for ( Integer topCatWord : topCatWords ) {
		for ( Integer leftCatWord : leftCatWords ) {
		for ( Integer rightCatWord : rightCatWords ) {
		for ( Integer leftLeftCatWord : leftLeftCatWords ) {
		for ( Integer leftRightCatWord : leftRightCatWords ) {
		for ( Integer rightLeftCatWord : rightLeftCatWords ) {
		for ( Integer rightRightCatWord : rightRightCatWords ) {
		for ( Integer topCatPos : topCatPoss ) {
		for ( Integer leftCatPos : leftCatPoss ) {
		for ( Integer rightCatPos : rightCatPoss ) {
		for ( Integer leftLeftCatPos : leftLeftCatPoss ) {
		for ( Integer leftRightCatPos : leftRightCatPoss ) {
		for ( Integer rightLeftCatPos : rightLeftCatPoss ) {
		for ( Integer rightRightCatPos : rightRightCatPoss ) {
			Feature feature = new Feature();
			feature.add(topCat);
			feature.add(leftCat);
			feature.add(rightCat);
			feature.add(leftLeftCat);
			feature.add(leftRightCat);
			feature.add(rightLeftCat);
			feature.add(rightRightCat);
			feature.add(topCatWord.toString());
			feature.add(leftCatWord.toString());
			feature.add(rightCatWord.toString());
			feature.add(leftLeftCatWord.toString());
			feature.add(leftRightCatWord.toString());
			feature.add(rightLeftCatWord.toString());
			feature.add(rightRightCatWord.toString());
			feature.add(topCatPos.toString());
			feature.add(leftCatPos.toString());
			feature.add(rightCatPos.toString());
			feature.add(leftLeftCatPos.toString());
			feature.add(leftRightCatPos.toString());
			feature.add(rightLeftCatPos.toString());
			feature.add(rightRightCatPos.toString());
			features.add(feature);
		}}}}}}}}}}}}}}

		return features;
	}

	private void getWordPos(Sentence sentence, SuperCategory superCat, ArrayList<Integer> words, ArrayList<Integer> poss) {
		Variable var = superCat.vars[superCat.cat.var];

		for ( int i = 0; i < var.fillers.length && var.fillers[i] != Variable.SENTINEL; i++ ) {
			if ( var.fillers[i] == 0 ) {
				continue;
			}

			words.add(sentence.wordIDs.get(var.fillers[i] - 1));
			poss.add(sentence.postagIDs.get(var.fillers[i] - 1));
		}
	}

	public void printChartDeps(PrintWriter outChartDeps, Relations relations, Sentence sentence) {
		for ( Cell cell : chart.chart ) {
			for ( SuperCategory superCat : cell.getSuperCategories() ) {
				for ( FilledDependency filled : superCat.filledDeps ) {
					filled.printFullJslot(outChartDeps, relations, sentence);
				}
			}
		}

		outChartDeps.println();
	}

	public void printChartFeatures(PrintWriter outChartFeatures, Sentence sentence) {
		for ( Cell cell : chart.chart ) {
			for ( SuperCategory superCat : cell.getSuperCategories() ) {
				printFeature(outChartFeatures, sentence, superCat);
			}
		}

		outChartFeatures.println();
	}
}
