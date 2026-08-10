package de.jeffclan.InvUnload;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class UtilsTest {

	@Test
	void constructor_throwsUnsupportedOperationException() throws Exception {
		// Utils is a pure static-utility class (Effective Java item 4);
		// its private no-arg constructor exists only to reject
		// instantiation, so reflection is the only way to reach it - this
		// asserts real behavior (the constructor really does throw),
		// not a hack to fake coverage of unreachable code.
		Constructor<Utils> constructor = Utils.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		try {
			constructor.newInstance();
			fail("Expected UnsupportedOperationException");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof UnsupportedOperationException);
		}
	}

	@Test
	void isInteger_acceptsPlainDigits() {
		assertTrue(Utils.isInteger("5"));
		assertTrue(Utils.isInteger("0"));
		assertTrue(Utils.isInteger("-3"));
	}

	@Test
	void isInteger_rejectsNonNumericText() {
		assertFalse(Utils.isInteger("five"));
		assertFalse(Utils.isInteger("5.5"));
		assertFalse(Utils.isInteger(""));
	}

	@Test
	void isInteger_rejectsNull() {
		assertFalse(Utils.isInteger(null));
	}
}
