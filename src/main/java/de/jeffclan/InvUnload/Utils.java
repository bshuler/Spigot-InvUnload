package de.jeffclan.InvUnload;

public class Utils {

	// Pure static-utility class - never meant to be instantiated (Effective
	// Java item 4 pattern). Was an implicit public no-arg constructor before
	// this pass, which JaCoCo always reported as an uncovered method since
	// nothing ever legitimately calls it; an explicit constructor that
	// throws is honestly testable instead of being a silent coverage gap.
	private Utils() {
		throw new UnsupportedOperationException("Utils is a static-only utility class and cannot be instantiated");
	}

	public static boolean isInteger(String s) {
	    try {
	        Integer.parseInt(s);
	    } catch (NumberFormatException e) {
	        // Bug found while writing tests for this class (see PLAN.md):
	        // this used to also catch NullPointerException, but
	        // Integer.parseInt(null) has thrown NumberFormatException (not
	        // NullPointerException) since at least early Java releases -
	        // confirmed with a standalone repro against the Java 25
	        // toolchain this project targets. The NullPointerException
	        // catch clause was dead code, unreachable by any input, and
	        // was removed rather than excluded from coverage.
	        return false;
	    }

	    return true;
	}
}
