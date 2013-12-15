package edu.stanford.nlp.sempre.test;

import edu.stanford.nlp.sempre.*;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertEquals;

/**
 * Test JavaExecutor.
 * @author Percy Liang
 */
public class JavaExecutorTest {
  JavaExecutor executor = new JavaExecutor();

  private static Formula F(String s) { return Formula.fromString(s); }

  private static Value V(double x) { return new NumberValue(x); }
  private static Value V(String x) { return new StringValue(x); }

  @Test public void numbers() {
    assertEquals(V(8), executor.execute(F("(call + (number 3) (number 5))")).value);
    assertEquals(V(6), executor.execute(F("(call + (call - (number 10) (number 9)) (number 5))")).value);
    assertEquals(V(1), executor.execute(F("(call java.lang.Math.cos (number 0))")).value);

    assertEquals(V(1), executor.execute(F("((lambda x (call java.lang.Math.cos (var x))) (number 0))")).value);  // Make sure beta reduction is called
  }

  @Test public void conditionals() {
    assertEquals(V("no"), executor.execute(F("(call if (boolean false) (string yes) (string no))")).value);
    assertEquals(V("yes"), executor.execute(F("(call if (call < (number 3) (number 4)) (string yes) (string no))")).value);
  }

  @Test public void strings() {
    assertEquals(V(5), executor.execute(F("(call .length (string hello))")).value);
    assertEquals(V("abcdef"), executor.execute(F("(call .concat (string abc) (string def))")).value);
  }
}