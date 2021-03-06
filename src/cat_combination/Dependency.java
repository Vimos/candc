package cat_combination;

import java.util.ArrayList;

import lexicon.Category;
import utils.Hash;

/*
 * this is the class for unfilled dependencies, with a var for the
 * filler; there is also a FilledDependency class which has a constant
 * (word index) as the filler
 */

public class Dependency implements Comparable<Dependency> {
	protected final short relID;
	protected final short headIndex; // position of the "head" word in the sentence
	protected final byte var; // varID associated with the filler
	protected final short unaryRuleID; // if dependency has been created thro' a unary rule
	protected final short lrange; // if dependency has been created thro' the head-passing mechanism

	protected final short conjFactor; // average divisor for multiple slot fillers in max-recall decoder

	public Dependency(short relID,
			short headIndex,
			byte var,
			short unaryRuleID) {
		this.relID = relID;
		this.headIndex = headIndex;
		this.var = var;
		this.unaryRuleID = unaryRuleID;
		this.conjFactor = 1;
		this.lrange = 0;

		if ( headIndex == 0 ) {
			throw new Error("expecting a non-zero head index when constructing the dependency!");
		}
	}

	/*
	 * this constructor is used in the clone method which is used in the
	 * UnaryRule SuperCategory constructor
	 */
	public Dependency(Dependency other, byte var, short unaryRuleID) {
		this.relID = other.relID;
		this.headIndex = other.headIndex;
		this.var = var;
		this.unaryRuleID = unaryRuleID;
		this.conjFactor = other.conjFactor;
		this.lrange = other.lrange;
	}

	/*
	 * note the arbitrary way in which the lrange variable is chosen; we never
	 * did work out a more motivated way of deciding when there are 2 options
	 * 
	 * we need the boolean at the end to distinguish this constructor from the
	 * one above with the same signature
	 */
	public Dependency(Dependency other,
			byte var,
			short lrange,
			boolean lrangeArg) {
		this.relID = other.relID;
		this.headIndex = other.headIndex;
		this.var = var;
		this.unaryRuleID = other.unaryRuleID;
		this.conjFactor = other.conjFactor;
		this.lrange = (lrange > other.lrange) ? lrange : other.lrange;
	}

	/*
	 * goes through the Category object collecting all the relations, creating
	 * dependencies for each one
	 */
	private static void get(short headIndex, Category cat, short ruleID, ArrayList<Dependency> resultDeps) {
		if ( cat.relID != 0 ) {
			resultDeps.add(new Dependency(cat.relID, headIndex, cat.var, ruleID));
		}

		if ( cat.result != null ) {
			get(headIndex, cat.result, ruleID, resultDeps);
			get(headIndex, cat.argument, ruleID, resultDeps);
		}
	}

	/*
	 * goes through the Category object collecting all the relations, creating
	 * dependencies for each one, and for each headIndex on the Variable object
	 */
	private static void get(Variable variable, Category cat, short ruleID, ArrayList<Dependency> resultDeps) {
		if ( cat.relID != 0 ) {
			for ( short filler : variable.fillers ) {
				if ( filler == Variable.SENTINEL ) {
					break;
				} else if ( filler != 0 ) {
					resultDeps.add(new Dependency(cat.relID, filler, cat.var, ruleID));
				}
			}
		}

		if ( cat.result != null ) {
			get(variable, cat.result, ruleID, resultDeps);
			get(variable, cat.argument, ruleID, resultDeps);
		}
	}

	public static ArrayList<Dependency> getDependencies(short headIndex, Category cat, short ruleID) {
		ArrayList<Dependency> deps = new ArrayList<Dependency>();

		if ( cat.isBasic() ) {
			if ( cat.relID != 0 ) {
				deps.add(new Dependency(cat.relID, headIndex, cat.var, ruleID));
			}
		} else {
			get(headIndex, cat, ruleID, deps);
		}

		return deps;
	}

	public static ArrayList<Dependency> getDependencies(Variable var, Category cat, short ruleID) {
		ArrayList<Dependency> deps = new ArrayList<Dependency>();
		get(var, cat, ruleID, deps);

		return deps;
	}

	public static ArrayList<Dependency> clone(byte from, byte to, short ruleID, ArrayList<Dependency> source) {
		ArrayList<Dependency> deps = new ArrayList<Dependency>();

		for ( Dependency dep : source ) {
			if ( dep.var == from ) {
				deps.add(new Dependency(dep, to, ruleID));
			}
		}

		return deps;
	}

	@Override
	public int compareTo(Dependency other) {
		int compare;
		if ( (compare = Short.compare(this.relID, other.relID)) != 0 ) { return compare; }
		if ( (compare = Short.compare(this.headIndex, other.headIndex)) != 0 ) { return compare; }
		if ( (compare = Byte.compare(this.var, other.var)) != 0 ) { return compare; }
		if ( (compare = Short.compare(this.lrange, other.lrange)) != 0 ) { return compare; }
		if ( (compare = Short.compare(this.unaryRuleID, other.unaryRuleID)) != 0 ) { return compare; }

		return 0;
	}

	@Override
	public int hashCode() {
		Hash h = new Hash(relID);
		h.plusEqual(headIndex);
		h.plusEqual(var);
		h.plusEqual(lrange);
		h.plusEqual(unaryRuleID);
		// h.plusEqual(conjFactor);

		return (int) (h.value());
	}

	@Override
	public boolean equals(Object other) {
		if ( other == null || getClass() != other.getClass() ) {
			return false;
		}

		Dependency cother = (Dependency) other;

		return compareTo(cother) == 0;
	}
}
