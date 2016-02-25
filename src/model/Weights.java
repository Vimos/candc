package model;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import io.Preface;

public class Weights {
	private double[] weights;

	public Weights() {
	}

	public Weights(String weightsFile, int numFeatures) throws IOException {
		this.weights = new double[numFeatures];

		if (!weightsFile.equals("zero")) {
			readWeights(weightsFile);
		}
	}

	public void setWeights(double[] weights) {
		this.weights = weights.clone();
	}

	private void readWeights(String weightsFile) throws IOException {
		int ID = 0;

		try ( BufferedReader in = new BufferedReader(new FileReader(weightsFile)) ) {

			Preface.readPreface(in);

			String line = null;
			while ((line = in.readLine()) != null) {
				double weight = Double.parseDouble(line);
				weights[ID] = weight;
				ID++;
			}

			if (ID != weights.length) {
				throw new IllegalArgumentException("number of weights != number of features!");
			}
		}
	}

	public double getWeight(int ID) {
		return weights[ID];
	}

	public void setWeight(int ID, double weight) {
		weights[ID] = weight;
	}
}
