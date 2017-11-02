package edu.stanford.nlp.sempre;

import java.util.Map;

import fig.basic.LogInfo;
import gnu.trove.map.TObjectDoubleMap;

/**
 * Created by joberant on 10/18/14.
 */
public final class SempreUtils {
  private SempreUtils() { }

  // "java.util.ArrayList" => "java.util.ArrayList"
  // "TypeLookup" => "edu.stanford.nlp.sempre.TypeLookup"
  public static String resolveClassName(String name) {
    if (name.startsWith("edu.") || name.startsWith("org.") ||
        name.startsWith("com.") || name.startsWith("net."))
      return name;
    return "edu.stanford.nlp.sempre." + name;
  }
}
