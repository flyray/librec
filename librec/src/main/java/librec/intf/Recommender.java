// Copyright (C) 2014 Guibing Guo
//
// This file is part of LibRec.
//
// LibRec is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// LibRec is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with LibRec. If not, see <http://www.gnu.org/licenses/>.
//

package librec.intf;

import happy.coding.io.FileConfiger;
import happy.coding.io.FileIO;
import happy.coding.io.LineConfiger;
import happy.coding.io.Lists;
import happy.coding.io.Logs;
import happy.coding.io.Strings;
import happy.coding.math.Measures;
import happy.coding.math.Randoms;
import happy.coding.math.Sims;
import happy.coding.math.Stats;
import happy.coding.system.Dates;
import happy.coding.system.Debug;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import librec.data.AddConfiguration;
import librec.data.Configuration;
import librec.data.DataDAO;
import librec.data.DataSplitter;
import librec.data.MatrixEntry;
import librec.data.SparseMatrix;
import librec.data.SparseVector;
import librec.data.SymmMatrix;

import com.google.common.base.Stopwatch;
import com.google.common.cache.LoadingCache;

/**
 * General recommenders
 * 
 * @author Guibing Guo
 */
@Configuration
public abstract class Recommender implements Runnable {

	/************************************ Static parameters for all recommenders ***********************************/
	// configer
	public static FileConfiger cf;
	// matrix of rating data
	public static SparseMatrix rateMatrix;

	// default temporary file directory
	public static String tempDirPath = "./Results/";

	// params used for multiple runs
	public static Map<String, List<Float>> params = new HashMap<>();

	// Guava cache configuration
	protected static String cacheSpec;

	// verbose
	protected static boolean verbose;

	// line configer for item ranking, evaluation
	protected static LineConfiger rankOptions, algoOptions;

	// is ranking/rating prediction
	public static boolean isRankingPred;
	// threshold to binarize ratings
	public static float binThold;
	// the ratio of validation data split from training data
	public static float validationRatio;
	// is diversity-based measures used
	protected static boolean isDiverseUsed;
	// is output recommendation results 
	protected static boolean isResultsOut;
	// is save model
	protected static boolean isSaveModel;
	// view of rating predictions
	public static String view;

	// rate DAO object
	public static DataDAO rateDao;

	// number of users, items, ratings
	protected static int numUsers, numItems, numRates;
	// number of recommended items
	protected static int numRecs, numIgnore;

	// a list of rating scales
	protected static List<Double> scales;
	// number of rating levels
	protected static int numLevels;
	// Maximum, minimum values of rating scales
	protected static double maxRate, minRate;
	// init mean and standard deviation
	protected static double initMean, initStd;

	/************************************ Recommender-specific parameters ****************************************/
	// algorithm's name
	public String algoName;
	// current fold
	protected int fold;
	// fold information
	protected String foldInfo;

	// user-vector cache, item-vector cache
	protected LoadingCache<Integer, SparseVector> userCache, itemCache;

	// user-items cache, item-users cache
	protected LoadingCache<Integer, List<Integer>> userItemsCache, itemUsersCache;

	// rating matrix for training, validation and test
	protected SparseMatrix trainMatrix, validationMatrix, testMatrix;

	// upper symmetric matrix of item-item correlations
	protected SymmMatrix corrs;

	// performance measures
	public Map<Measure, Double> measures;
	// global average of training rates
	protected double globalMean;

	/**
	 * Recommendation measures
	 * 
	 */
	public enum Measure {
		MAE, RMSE, NMAE, ASYMM, MPE, D5, D10, Pre5, Pre10, Rec5, Rec10, MAP, MRR, NDCG, AUC, TrainTime, TestTime
	}

	/**
	 * Constructor for Recommender
	 * 
	 * @param trainMatrix
	 *            train matrix
	 * @param testMatrix
	 *            test matrix
	 */
	public Recommender(SparseMatrix trainMatrix, SparseMatrix testMatrix, int fold) {

		// config recommender
		if (cf == null || rateMatrix == null) {
			Logs.error("Recommender is not well configed");
			System.exit(-1);
		}

		// static initialization, only done once
		if (scales == null) {
			scales = rateDao.getScales();
			minRate = scales.get(0);
			maxRate = scales.get(scales.size() - 1);
			numLevels = scales.size();

			numUsers = rateDao.numUsers();
			numItems = rateDao.numItems();

			initMean = 0.0;
			initStd = 0.1;

			verbose = cf.isOn("is.verbose");
			cacheSpec = cf.getString("guava.cache.spec");

			rankOptions = cf.getParamOptions("item.ranking");
			isRankingPred = Strings.isOn(rankOptions.getMainParam());
			isDiverseUsed = rankOptions.contains("-diverse");
			numRecs = rankOptions.getInt("-topN", -1);
			numIgnore = rankOptions.getInt("-ignore", -1);

			LineConfiger evalOptions = cf.getParamOptions("evaluation.setup");
			view = evalOptions.getString("--test-view", "all");
			validationRatio = evalOptions.getFloat("-v", 0.0f);
			isResultsOut = evalOptions.isOn("-o", false);
			isSaveModel = evalOptions.isOn("--save-model", false);

			// initial random seed
			int seed = cf.getInt("num.rand.seed");
			Randoms.seed(seed <= 0 ? System.currentTimeMillis() : seed);
		}

		// training, validation, test data
		if (validationRatio > 0 && validationRatio < 1) {
			SparseMatrix[] trainSubsets = new DataSplitter(trainMatrix).getRatio(1 - validationRatio);
			this.trainMatrix = trainSubsets[0];
			this.validationMatrix = trainSubsets[1];
		} else {
			this.trainMatrix = trainMatrix;
		}

		this.testMatrix = testMatrix;

		// fold info
		this.fold = fold;
		foldInfo = fold > 0 ? " fold [" + fold + "]" : "";

		// global mean
		numRates = trainMatrix.size();
		globalMean = trainMatrix.sum() / numRates;

		// class name as the default algorithm name
		algoName = this.getClass().getSimpleName();
		// get parameters of an algorithm
		algoOptions = getModelParams(algoName);

		// compute item-item correlations
		if (isRankingPred && isDiverseUsed)
			corrs = new SymmMatrix(numItems);
	}

	public void run() {
		try {
			execute();
		} catch (Exception e) {
			// capture error message
			Logs.error(e.getMessage());

			e.printStackTrace();
		}
	}

	/**
	 * execution method of a recommender
	 * 
	 */
	public void execute() throws Exception {

		Stopwatch sw = Stopwatch.createStarted();
		if (Debug.ON) {
			// learn a recommender model
			initModel();

			// print out algorithm's settings: to indicate starting building models
			String algoInfo = toString();

			Class<? extends Recommender> cl = this.getClass();
			String algoConfig = cl.getAnnotation(Configuration.class).value(); // basic annotation
			// additional algorithm-specific configuration
			if (cl.isAnnotationPresent(AddConfiguration.class)) {
				algoConfig += ", " + cl.getAnnotation(AddConfiguration.class).value();
			}

			if (!algoInfo.isEmpty()) {
				if (!algoConfig.isEmpty())
					Logs.debug("{}: [{}] = [{}]", algoName, algoConfig, algoInfo);
				else
					Logs.debug("{}: {}", algoName, algoInfo);
			}

			buildModel();

			// post-processing after building a model, e.g., release intermediate memory to avoid memory leak
			postModel();
		} else {
			/**
			 * load a learned model: this code will not be executed unless "Debug.OFF" mainly for the purpose of
			 * exemplifying how to use the saved models
			 */
			loadModel();
		}
		long trainTime = sw.elapsed(TimeUnit.MILLISECONDS);

		// validation
		if (validationRatio > 0 && validationRatio < 1) {
			validateModel();

			trainTime = sw.elapsed(TimeUnit.MILLISECONDS);
		}

		// evaluation
		if (verbose)
			Logs.debug("{}{} evaluate test data ... ", algoName, foldInfo);
		measures = isRankingPred ? evalRankings() : evalRatings();
		String result = getEvalInfo(measures);
		sw.stop();
		long testTime = sw.elapsed(TimeUnit.MILLISECONDS) - trainTime;

		// collecting results
		measures.put(Measure.TrainTime, (double) trainTime);
		measures.put(Measure.TestTime, (double) testTime);

		String evalInfo = algoName + foldInfo + ": " + result + "\tTime: "
				+ Dates.parse(measures.get(Measure.TrainTime).longValue()) + ", "
				+ Dates.parse(measures.get(Measure.TestTime).longValue());
		if (!isRankingPred)
			evalInfo += "\tView: " + view;

		if (fold > 0)
			Logs.debug(evalInfo);

		if (isSaveModel)
			saveModel();
	}

	/**
	 * validate model with held-out validation data
	 */
	protected void validateModel() {
	}

	/**
	 * @return the evaluation information of a recommend
	 */
	public static String getEvalInfo(Map<Measure, Double> measures) {
		String evalInfo = null;
		if (isRankingPred) {
			// Note: MAE and RMSE are computed, but not used here
			// .... if you need them, add it back in the same manner as other
			// metrics
			if (isDiverseUsed)
				evalInfo = String.format("%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%2d",
						measures.get(Measure.D5), measures.get(Measure.D10), measures.get(Measure.Pre5),
						measures.get(Measure.Pre10), measures.get(Measure.Rec5), measures.get(Measure.Rec10),
						measures.get(Measure.AUC), measures.get(Measure.MAP), measures.get(Measure.NDCG),
						measures.get(Measure.MRR), numIgnore);
			else
				evalInfo = String.format("%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%2d", measures.get(Measure.Pre5),
						measures.get(Measure.Pre10), measures.get(Measure.Rec5), measures.get(Measure.Rec10),
						measures.get(Measure.AUC), measures.get(Measure.MAP), measures.get(Measure.NDCG),
						measures.get(Measure.MRR), numIgnore);
		} else
			evalInfo = String.format("%.6f,%.6f,%.6f,%.6f,%.6f", measures.get(Measure.MAE), measures.get(Measure.RMSE),
					measures.get(Measure.NMAE), measures.get(Measure.ASYMM), measures.get(Measure.MPE));

		return evalInfo;
	}

	/**
	 * initilize recommender model
	 */
	protected void initModel() throws Exception {
	}

	protected LineConfiger getModelParams(String algoName) {
		return cf.contains(algoName) ? cf.getParamOptions(algoName) : null;
	}

	/**
	 * build user-user or item-item correlation matrix from training data
	 * 
	 * @param isUser
	 *            whether it is user-user correlation matrix
	 * 
	 * @return a upper symmetric matrix with user-user or item-item coefficients
	 * 
	 */
	protected SymmMatrix buildCorrs(boolean isUser) {
		Logs.debug("Build {} similarity matrix ...", isUser ? "user" : "item");

		int count = isUser ? numUsers : numItems;
		SymmMatrix corrs = new SymmMatrix(count);

		for (int i = 0; i < count; i++) {
			SparseVector iv = isUser ? trainMatrix.row(i) : trainMatrix.column(i);
			if (iv.getCount() == 0)
				continue;
			// user/item itself exclusive
			for (int j = i + 1; j < count; j++) {
				SparseVector jv = isUser ? trainMatrix.row(j) : trainMatrix.column(j);

				double sim = correlation(iv, jv);

				if (!Double.isNaN(sim))
					corrs.set(i, j, sim);
			}
		}

		return corrs;
	}

	/**
	 * Compute the correlation between two vectors using method specified by configuration key "similarity"
	 * 
	 * @param iv
	 *            vector i
	 * @param jv
	 *            vector j
	 * @return the correlation between vectors i and j
	 */
	protected double correlation(SparseVector iv, SparseVector jv) {
		return correlation(iv, jv, cf.getString("similarity"));
	}

	/**
	 * Compute the correlation between two vectors for a specific method
	 * 
	 * @param iv
	 *            vector i
	 * @param jv
	 *            vector j
	 * @param method
	 *            similarity method
	 * @return the correlation between vectors i and j; return NaN if the correlation is not computable.
	 */
	protected double correlation(SparseVector iv, SparseVector jv, String method) {

		// compute similarity
		List<Double> is = new ArrayList<>();
		List<Double> js = new ArrayList<>();

		for (Integer idx : jv.getIndex()) {
			if (iv.contains(idx)) {
				is.add(iv.get(idx));
				js.add(jv.get(idx));
			}
		}

		double sim = 0;
		switch (method.toLowerCase()) {
		case "cos":
			// for ratings along the overlappings
			sim = Sims.cos(is, js);
			break;
		case "cos-binary":
			// for ratings along all the vectors (including one-sided 0s)
			sim = iv.inner(jv) / (Math.sqrt(iv.inner(iv)) * Math.sqrt(jv.inner(jv)));
			break;
		case "msd":
			sim = Sims.msd(is, js);
			break;
		case "cpc":
			sim = Sims.cpc(is, js, (minRate + maxRate) / 2.0);
			break;
		case "exjaccard":
			sim = Sims.exJaccard(is, js);
			break;
		case "pcc":
		default:
			sim = Sims.pcc(is, js);
			break;
		}

		// shrink to account for vector size
		if (!Double.isNaN(sim)) {
			int n = is.size();
			int shrinkage = cf.getInt("num.shrinkage");
			if (shrinkage > 0)
				sim *= n / (n + shrinkage + 0.0);
		}

		return sim;
	}

	/**
	 * Learning method: override this method to build a model, for a model-based method. Default implementation is
	 * useful for memory-based methods.
	 * 
	 */
	protected void buildModel() throws Exception {
	}

	/**
	 * After learning model: release some intermediate data to avoid memory leak
	 */
	protected void postModel() throws Exception {
	}

	/**
	 * Serializing a learned model (i.e., variable data) to files.
	 */
	protected void saveModel() throws Exception {
	}

	/**
	 * Deserializing a learned model (i.e., variable data) from files.
	 */
	protected void loadModel() throws Exception {
	}

	/**
	 * determine whether the rating of a user-item (u, j) is used to predicted
	 * 
	 */
	protected boolean isTestable(int u, int j) {
		switch (view) {
		case "cold-start":
			return trainMatrix.rowSize(u) < 5 ? true : false;
		case "all":
		default:
			return true;
		}
	}

	/**
	 * @return the evaluation results of rating predictions
	 */
	private Map<Measure, Double> evalRatings() throws Exception {

		List<String> preds = null;
		String toFile = null;
		if (isResultsOut) {
			preds = new ArrayList<String>(1500);
			preds.add("# userId itemId rating prediction"); // optional: file header
			FileIO.makeDirectory(tempDirPath); // in case that the fold does not exist
			toFile = tempDirPath + algoName + "-rating-predictions" + foldInfo + ".txt"; // the output-file name
			FileIO.deleteFile(toFile); // delete possibly old files
		}

		double sum_maes = 0, sum_mses = 0, sum_asyms = 0;
		int numCount = 0;
		int numPEs = 0; // number of prediction errors in terms of classification
		for (MatrixEntry me : testMatrix) {
			double rate = me.get();

			int u = me.row();
			int j = me.column();

			if (!isTestable(u, j))
				continue;

			double pred = predict(u, j, true);
			if (Double.isNaN(pred))
				continue;

			double err = Math.abs(rate - pred); // absolute predictive error

			sum_maes += err;
			sum_mses += err * err;
			sum_asyms += Measures.ASYMMLoss(rate, pred, minRate, maxRate);

			if (err > 1e-5) // if errors cannot be ignored
				numPEs++;

			numCount++;

			// output predictions
			if (isResultsOut) {
				// restore back to the original user/item id
				preds.add(rateDao.getUserId(u) + " " + rateDao.getItemId(j) + " " + rate + " " + (float) pred);
				if (preds.size() >= 1000) {
					FileIO.writeList(toFile, preds, true);
					preds.clear();
				}
			}
		}

		if (isResultsOut && preds.size() > 0) {
			FileIO.writeList(toFile, preds, true);
			Logs.debug("{}{} has writeen rating predictions to {}", algoName, foldInfo, toFile);
		}

		double mae = sum_maes / numCount;
		double rmse = Math.sqrt(sum_mses / numCount);
		double asymm = sum_asyms / numCount;
		double mpe = (numPEs + 0.0) / numCount; // mean prediction error

		Map<Measure, Double> measures = new HashMap<>();
		measures.put(Measure.MAE, mae);
		// normalized MAE: useful for direct comparison among different data sets with distinct rating scales
		measures.put(Measure.NMAE, mae / (maxRate - minRate));
		measures.put(Measure.RMSE, rmse);
		measures.put(Measure.ASYMM, asymm);
		// mean prediction error: the percentage of predictions which differ from the actual rating values
		measures.put(Measure.MPE, mpe);

		return measures;
	}

	/**
	 * @return the evaluation results of ranking predictions
	 */
	private Map<Measure, Double> evalRankings() throws Exception {

		int capacity = Lists.initSize(testMatrix.numRows());

		// initialization capacity to speed up
		List<Double> ds5 = new ArrayList<>(isDiverseUsed ? capacity : 0);
		List<Double> ds10 = new ArrayList<>(isDiverseUsed ? capacity : 0);

		List<Double> precs5 = new ArrayList<>(capacity);
		List<Double> precs10 = new ArrayList<>(capacity);
		List<Double> recalls5 = new ArrayList<>(capacity);
		List<Double> recalls10 = new ArrayList<>(capacity);
		List<Double> aps = new ArrayList<>(capacity);
		List<Double> rrs = new ArrayList<>(capacity);
		List<Double> aucs = new ArrayList<>(capacity);
		List<Double> ndcgs = new ArrayList<>(capacity);

		// candidate items for all users: here only training items
		// use HashSet instead of ArrayList to speedup removeAll() and contains() operations: HashSet: O(1); ArrayList: O(log n).
		Set<Integer> candItems = new HashSet<>(trainMatrix.columns());

		List<String> preds = null;
		String toFile = null;
		if (isResultsOut) {
			preds = new ArrayList<String>(1500);
			preds.add("# userId: recommendations in (itemId, ranking score) pairs, where a correct recommendation is denoted by symbol *."); // optional: file header
			FileIO.makeDirectory(tempDirPath); // in case that the fold does not exist
			toFile = tempDirPath + algoName + "-top-10-items" + foldInfo + ".txt"; // the output-file name
			FileIO.deleteFile(toFile); // delete possibly old files
		}

		if (verbose)
			Logs.debug("{}{} has candidate items: {}", algoName, foldInfo, candItems.size());

		// ignore items for all users: most popular items
		if (numIgnore > 0) {
			List<Map.Entry<Integer, Integer>> itemDegs = new ArrayList<>();
			for (Integer j : candItems) {
				itemDegs.add(new SimpleImmutableEntry<Integer, Integer>(j, trainMatrix.columnSize(j)));
			}
			Lists.sortList(itemDegs, true);
			int k = 0;
			for (Map.Entry<Integer, Integer> deg : itemDegs) {

				// ignore these items from candidate items
				candItems.remove(deg.getKey());
				if (++k >= numIgnore)
					break;
			}
		}

		// for each test user
		for (int u = 0, um = testMatrix.numRows(); u < um; u++) {

			if (verbose && ((u + 1) % 100 == 0))
				Logs.debug("{}{} evaluates progress: {} / {}", algoName, foldInfo, u + 1, um);

			// number of candidate items for all users
			int numCands = candItems.size();

			// get positive items from test matrix
			List<Integer> testItems = testMatrix.getColumns(u);
			List<Integer> correctItems = new ArrayList<>();

			// intersect with the candidate items
			for (Integer j : testItems) {
				if (candItems.contains(j))
					correctItems.add(j);
			}

			if (correctItems.size() == 0)
				continue; // no testing data for user u

			// remove rated items from candidate items
			List<Integer> ratedItems = trainMatrix.getColumns(u);

			// predict the ranking scores (unordered) of all candidate items
			List<Map.Entry<Integer, Double>> itemScores = new ArrayList<>(Lists.initSize(candItems));
			for (final Integer j : candItems) {
				// item j is not rated 
				if (!ratedItems.contains(j)) {
					final double rank = ranking(u, j);
					if (!Double.isNaN(rank)) {
						itemScores.add(new SimpleImmutableEntry<Integer, Double>(j, rank));
					}
				} else {
					numCands--;
				}
			}

			if (itemScores.size() == 0)
				continue; // no recommendations available for user u

			// order the ranking scores from highest to lowest: List to preserve orders
			Lists.sortList(itemScores, true);
			List<Map.Entry<Integer, Double>> recomd = (numRecs <= 0 || itemScores.size() <= numRecs) ? itemScores
					: itemScores.subList(0, numRecs);

			List<Integer> rankedItems = new ArrayList<>();
			StringBuilder sb = new StringBuilder();
			int count = 0;
			for (Map.Entry<Integer, Double> kv : recomd) {
				Integer item = kv.getKey();
				rankedItems.add(item);

				if (isResultsOut && count < 10) {
					// restore back to the original item id
					sb.append("(").append(rateDao.getItemId(item));

					if (testItems.contains(item))
						sb.append("*"); // indicating correct recommendation

					sb.append(", ").append(kv.getValue().floatValue()).append(")");

					if (++count >= 10)
						break;
					if (count < 10)
						sb.append(", ");
				}
			}

			int numDropped = numCands - rankedItems.size();
			double AUC = Measures.AUC(rankedItems, correctItems, numDropped);
			double AP = Measures.AP(rankedItems, correctItems);
			double nDCG = Measures.nDCG(rankedItems, correctItems);
			double RR = Measures.RR(rankedItems, correctItems);

			List<Integer> cutoffs = Arrays.asList(5, 10);
			Map<Integer, Double> precs = Measures.PrecAt(rankedItems, correctItems, cutoffs);
			Map<Integer, Double> recalls = Measures.RecallAt(rankedItems, correctItems, cutoffs);

			precs5.add(precs.get(5));
			precs10.add(precs.get(10));
			recalls5.add(recalls.get(5));
			recalls10.add(recalls.get(10));

			aucs.add(AUC);
			aps.add(AP);
			rrs.add(RR);
			ndcgs.add(nDCG);

			// diversity
			if (isDiverseUsed) {
				double d5 = diverseAt(rankedItems, 5);
				double d10 = diverseAt(rankedItems, 10);

				ds5.add(d5);
				ds10.add(d10);
			}

			// output predictions
			if (isResultsOut) {
				// restore back to the original user id
				preds.add(rateDao.getUserId(u) + ": " + sb.toString());
				if (preds.size() >= 1000) {
					FileIO.writeList(toFile, preds, true);
					preds.clear();
				}
			}
		}

		// write results out first
		if (isResultsOut && preds.size() > 0) {
			FileIO.writeList(toFile, preds, true);
			Logs.debug("{}{} has writeen item recommendations to {}", algoName, foldInfo, toFile);
		}

		// measure the performance
		Map<Measure, Double> measures = new HashMap<>();
		measures.put(Measure.D5, isDiverseUsed ? Stats.mean(ds5) : 0.0);
		measures.put(Measure.D10, isDiverseUsed ? Stats.mean(ds10) : 0.0);
		measures.put(Measure.Pre5, Stats.mean(precs5));
		measures.put(Measure.Pre10, Stats.mean(precs10));
		measures.put(Measure.Rec5, Stats.mean(recalls5));
		measures.put(Measure.Rec10, Stats.mean(recalls10));
		measures.put(Measure.AUC, Stats.mean(aucs));
		measures.put(Measure.NDCG, Stats.mean(ndcgs));
		measures.put(Measure.MAP, Stats.mean(aps));
		measures.put(Measure.MRR, Stats.mean(rrs));

		return measures;
	}

	/**
	 * predict a specific rating for user u on item j. It is useful for evalution which requires predictions are
	 * bounded.
	 * 
	 * @param u
	 *            user id
	 * @param j
	 *            item id
	 * @param bound
	 *            whether to bound the prediction
	 * @return prediction
	 */
	protected double predict(int u, int j, boolean bound) throws Exception {
		double pred = predict(u, j);

		if (bound) {
			if (pred > maxRate)
				pred = maxRate;
			if (pred < minRate)
				pred = minRate;
		}

		return pred;
	}

	/**
	 * predict a specific rating for user u on item j, note that the prediction is not bounded. It is useful for
	 * building models with no need to bound predictions.
	 * 
	 * @param u
	 *            user id
	 * @param j
	 *            item id
	 * @return raw prediction without bounded
	 */
	protected double predict(int u, int j) throws Exception {
		return globalMean;
	}

	/**
	 * predict a ranking score for user u on item j: default case using the unbounded predicted rating values
	 * 
	 * @param u
	 *            user id
	 * 
	 * @param j
	 *            item id
	 * @return a ranking score for user u on item j
	 */
	protected double ranking(int u, int j) throws Exception {
		return predict(u, j, false);
	}

	/**
	 * 
	 * @param rankedItems
	 *            the list of ranked items to be recommended
	 * @param cutoff
	 *            cutoff in the list
	 * @param corrs
	 *            correlations between items
	 * @return diversity at a specific cutoff position
	 */
	protected double diverseAt(List<Integer> rankedItems, int cutoff) {

		int num = 0;
		double sum = 0.0;
		for (int id = 0; id < cutoff; id++) {
			int i = rankedItems.get(id);
			SparseVector iv = trainMatrix.column(i);

			for (int jd = id + 1; jd < cutoff; jd++) {
				int j = rankedItems.get(jd);

				double corr = corrs.get(i, j);
				if (corr == 0) {
					// if not found
					corr = correlation(iv, trainMatrix.column(j));
					if (!Double.isNaN(corr))
						corrs.set(i, j, corr);
				}

				if (!Double.isNaN(corr)) {
					sum += (1 - corr);
					num++;
				}
			}
		}

		return 0.5 * (sum / num);
	}

	/**
	 * Below are a set of mathematical functions. As many recommenders often adopts them, for conveniency's sake, we put
	 * these functions in the base Recommender class, though they belong to Math class.
	 * 
	 */

	/**
	 * logistic function g(x)
	 */
	protected double g(double x) {
		return 1.0 / (1 + Math.exp(-x));
	}

	/**
	 * gradient value of logistic function g(x)
	 */
	protected double gd(double x) {
		return g(x) * g(-x);
	}

	/**
	 * @param x
	 *            input value
	 * @param mu
	 *            mean of normal distribution
	 * @param sigma
	 *            standard deviation of normation distribution
	 * 
	 * @return a gaussian value with mean {@code mu} and standard deviation {@code sigma};
	 */
	protected double gaussian(double x, double mu, double sigma) {
		return Math.exp(-0.5 * Math.pow(x - mu, 2) / (sigma * sigma));
	}

	/**
	 * normalize a rating to the region (0, 1)
	 */
	protected double normalize(double rate) {
		return (rate - minRate) / (maxRate - minRate);
	}

	/**
	 * Check if ratings have been binarized; useful for methods that require binarized ratings;
	 */
	protected void checkBinary() {
		if (binThold < 0) {
			Logs.error("val.binary.threshold={}, ratings must be binarized first! Try set a non-negative value.",
					binThold);
			System.exit(-1);
		}
	}

	/**
	 * 
	 * denormalize a prediction to the region (minRate, maxRate)
	 */
	protected double denormalize(double pred) {
		return minRate + pred * (maxRate - minRate);
	}

	/**
	 * useful to print out specific recommender's settings
	 */
	@Override
	public String toString() {
		return "";
	}
}
