package edu.stanford.nlp.sempre;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.sequences.DocumentReaderAndWriter;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.PaddedList;

/**
 * A copy of edu.stanford.nlp.ie.regexp.NumberSequenceClassifier, with
 * some bugfixes and without sutime and a ton of other unneeded stuff.
 * 
 * Have been removed (compared to CoreNLP):
 * - SUTime
 * - Generic time words
 * - Handling of "m" and "b" to mean million and billion
 * Have been added
 * - Month ORDINAL
 * Have been fixed
 * - months are case-insensitive
 * 
 * The rest of the CoreNLP doc-string follows:
 * 
 * A set of deterministic rules for marking certain entities, to add
 * categories and to correct for failures of statistical NER taggers.
 * This is an extremely simple and ungeneralized implementation of
 * AbstractSequenceClassifier that was written for PASCAL RTE.
 * It could profitably be extended and generalized.
 * It marks a NUMBER category based on part-of-speech tags in a
 * deterministic manner.
 * It marks an ORDINAL category based on word form in a deterministic manner.
 * It tags as MONEY currency signs and things tagged CD after a currency sign.
 * It marks a number before a month name as a DATE.
 * It marks as a DATE a word of the form xx/xx/xxxx
 * (where x is a digit from a suitable range).
 * It marks as a TIME a word of the form x(x):xx (where x is a digit).
 * It marks everything else tagged "CD" as a NUMBER, and instances
 * of "and" appearing between CD tags in contexts suggestive of a number.
 * It requires text to be POS-tagged (have the getString(TagAnnotation.class)
 * attribute).
 * Effectively these rules assume that
 * this classifier will be used as a secondary classifier by
 * code such as ClassifierCombiner: it will mark most CD as NUMBER, and it
 * is assumed that something else with higher priority is marking ones that
 * are PERCENT, ADDRESS, etc.
 *
 * @author Christopher Manning
 * @author Mihai (integrated with NumberNormalizer, SUTime)
 */
public class NumberSequenceClassifier extends AbstractSequenceClassifier<CoreLabel> {

  public NumberSequenceClassifier() {
    super(new Properties());
  }

  private static final boolean DEBUG = false;

  private static final Pattern MONTH_PATTERN = Pattern.compile(
      "january|jan\\.?|february|feb\\.?|march|mar\\.?|april|apr\\.?|may|june|jun\\.?|july|jul\\.?|august|aug\\.?|september|sept?\\.?|october|oct\\.?|november|nov\\.?|december|dec\\.",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]+");

  private static final Pattern YEAR_PATTERN = Pattern.compile("[1-3][0-9]{3}|'?[0-9]{2}");

  private static final Pattern DAY_PATTERN = Pattern.compile("(?:[1-9]|[12][0-9]|3[01])(?:st|nd|rd)?");

  private static final Pattern WEEKDAY_PATTERN = Pattern.compile(
      "monday|mon|tuesday|tues?|wednesday|wed|thursday|thu|friday|fri|saturday|sat|sunday|sun",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern DATE_PATTERN = Pattern
      .compile("(?:[1-9]|[0-3][0-9])\\\\?/(?:[1-9]|[0-3][0-9])\\\\?/(?:[1-3][0-9]{3}|[0-9]{2})");

  private static final Pattern DATE_PATTERN2 = Pattern.compile("[12][0-9]{3}[-/](?:0?[1-9]|1[0-2])[-/][0-3][0-9]");

  private static final Pattern TIME_PATTERN = Pattern.compile("[0-2]?[0-9]:[0-5][0-9]");

  private static final Pattern TIME_PATTERN2 = Pattern.compile("[0-2][0-9]:[0-5][0-9]:[0-5][0-9]");

  private static final Pattern TIME_PATTERN3 = Pattern.compile("[0-2]?[0-9](:[0-5][0-9])?[ap]\\.?m\\.?",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern TIME_PATTERN4 = Pattern.compile("[0-2]?[0-9][0-5][0-9][ap]\\.?m?\\.?",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern AM_PM = Pattern.compile("(a\\.?m\\.?)|(p\\.?m\\.?)", Pattern.CASE_INSENSITIVE);

  public static final Pattern CURRENCY_WORD_PATTERN = Pattern.compile(
      "(?:dollar|cent|euro|buck)s?|penny|pence|yen|yuan|won",
      Pattern.CASE_INSENSITIVE);

  // pattern matches: dollar, pound sign XML escapes; pound sign, yen sign, euro, won; other country dollars; now omit # for pound
  // TODO: Delete # as currency.  But doing this involves changing PTBTokenizer currency normalization rules
  // Code \u0023 '#' was used for pound '£' in the ISO version of ASCII (ISO 646), and this is found in very old materials
  // e.g., the 1999 Penn Treebank, but we now don't recognize this, as it now doesn't occur and wrongly recognizes
  // currency whenever someone refers to the #4 country etc.
  public static final Pattern CURRENCY_SYMBOL_PATTERN = Pattern
      .compile("\\$|#|&#163;|&pound;|\u00A3|\u00A5|\u20AC|\u20A9|(?:US|HK|A|C|NT|S|NZ)\\$", Pattern.CASE_INSENSITIVE); // TODO: No longer include archaic # for pound

  private static final Pattern ORDINAL_PATTERN = Pattern.compile(
      "(?i)[2-9]?1st|[2-9]?2nd|[2-9]?3rd|1[0-9]th|[2-9]?[04-9]th|100+th|zeroth|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|twenty-first|twenty-second|twenty-third|twenty-fourth|twenty-fifth|twenty-sixth|twenty-seventh|twenty-eighth|twenty-ninth|thirtieth|thirty-first|fortieth|fiftieth|sixtieth|seventieth|eightieth|ninetieth|hundredth|thousandth|millionth");

  private static final Pattern ORDINAL_SUFFIX_PATTERN = Pattern.compile("st|nd|th", Pattern.CASE_INSENSITIVE);

  private static final Pattern ARMY_TIME_MORNING = Pattern.compile("0([0-9])([0-9]){2}");

  private static final Pattern PART_OF_DAY_PATTERN = Pattern.compile("noon|midnight|midday", Pattern.CASE_INSENSITIVE);

  /**
   * Classify a {@link List} of {@link CoreLabel}s.
   *
   * @param document
   *          A {@link List} of {@link CoreLabel}s.
   * @return the same {@link List}, but with the elements annotated
   *         with their answers.
   */
  @Override
  public List<CoreLabel> classify(List<CoreLabel> document) {
    // if (DEBUG) { System.err.println("NumberSequenceClassifier tagging"); }

    for (CoreLabel me : document) {
      // a thing made of numbers is a number, let's not be stupid about it
      if (NUMBER_PATTERN.matcher(me.word()).matches())
        me.setTag("CD");
    }

    PaddedList<CoreLabel> pl = new PaddedList<>(document, pad);
    for (int i = 0, sz = pl.size(); i < sz; i++) {
      CoreLabel me = pl.get(i);
      CoreLabel prev = pl.get(i - 1);
      CoreLabel next = pl.get(i + 1);
      CoreLabel next2 = pl.get(i + 2);
      //if (DEBUG) { System.err.println("Tagging:" + me.word()); }
      me.set(CoreAnnotations.AnswerAnnotation.class, flags.backgroundSymbol);

      /*if ("morning".equals(me.word()) || "evening".equals(me.word())) {
        if ("at".equals(next.word()) &&
            (TIME_PATTERN.matcher(next2.word()).matches() || TIME_PATTERN2.matcher(next2.word()).matches()
                || TIME_PATTERN3.matcher(next2.word()).matches())) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
          next.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
          next2.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
        }
      }*/
      if (CURRENCY_SYMBOL_PATTERN.matcher(me.word()).matches() &&
          (prev.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CD") ||
              next.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CD"))) {
        // dollar, pound, pound, yen,
        // Penn Treebank ancient # as pound, euro,
        if (DEBUG) {
          System.err.println("Found currency sign:" + me.word());
        }
        me.set(CoreAnnotations.AnswerAnnotation.class, "MONEY");
      } else if (TIME_PATTERN.matcher(me.word()).matches() || TIME_PATTERN2.matcher(me.word()).matches()
          || TIME_PATTERN3.matcher(me.word()).matches() || TIME_PATTERN4.matcher(me.word()).matches()) {
        me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
      } else if (DATE_PATTERN.matcher(me.word()).matches() || DATE_PATTERN2.matcher(me.word()).matches()) {
        me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
      } else if (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CD")) {
        if (DEBUG) {
          System.err.println("Tagging CD:" + me.word());
        }

        if (next.get(CoreAnnotations.TextAnnotation.class) != null &&
            me.get(CoreAnnotations.TextAnnotation.class) != null &&
            DAY_PATTERN.matcher(me.get(CoreAnnotations.TextAnnotation.class)).matches() &&
            MONTH_PATTERN.matcher(next.get(CoreAnnotations.TextAnnotation.class)).matches()) {
          // deterministically make DATE for British-style number before month
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
          if (prev.word() != null &&
              WEEKDAY_PATTERN.matcher(prev.word()).matches())
            prev.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        } else if (prev.get(CoreAnnotations.TextAnnotation.class) != null &&
            MONTH_PATTERN.matcher(prev.get(CoreAnnotations.TextAnnotation.class)).matches() &&
            me.get(CoreAnnotations.TextAnnotation.class) != null &&
            DAY_PATTERN.matcher(me.get(CoreAnnotations.TextAnnotation.class)).matches()) {
          // deterministically make DATE for number after month
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        } else if (ARMY_TIME_MORNING.matcher(me.word()).matches()) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
        } else if (YEAR_PATTERN.matcher(me.word()).matches() &&
            prev.getString(CoreAnnotations.AnswerAnnotation.class).equals("DATE") &&
            (MONTH_PATTERN.matcher(prev.word()).matches() ||
                pl.get(i - 2).get(CoreAnnotations.AnswerAnnotation.class).equals("DATE"))) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        } else if ("o'clock".equals(next.word())) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
        } else {
          if (DEBUG) {
            System.err.println("Found number:" + me.word());
          }
          if (prev.getString(CoreAnnotations.AnswerAnnotation.class).equals("MONEY")) {
            me.set(CoreAnnotations.AnswerAnnotation.class, "MONEY");
          } else {
            me.set(CoreAnnotations.AnswerAnnotation.class, "NUMBER");
          }
        }
      } else if (AM_PM.matcher(me.word()).matches() &&
          (prev.get(CoreAnnotations.AnswerAnnotation.class).equals("TIME") ||
              prev.get(CoreAnnotations.AnswerAnnotation.class).equals("NUMBER"))) {
        prev.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
        me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
      } else if (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class) != null &&
          me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals(",") &&
          prev.getString(CoreAnnotations.AnswerAnnotation.class).equals("DATE") &&
          next.word() != null && YEAR_PATTERN.matcher(next.word()).matches()) {
        me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
      } else if (MONTH_PATTERN.matcher(me.word()).matches()) {
        // sometimes the POS tag of a month is NNP and sometimes it's NN (and sometimes it's a VBD, because the POS tagger really sucks), take both to be sure
        if (prev.getString(CoreAnnotations.AnswerAnnotation.class).equals("DATE") ||
            next.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CD") ||
            next.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("JJ")) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");

          if (prev.word() != null &&
              WEEKDAY_PATTERN.matcher(prev.word()).matches())
            prev.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        }
      } else if (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class) != null &&
          me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CC")) {
        if (prev.tag() != null && prev.tag().equals("CD") &&
            next.tag() != null && next.tag().equals("CD") &&
            me.get(CoreAnnotations.TextAnnotation.class) != null &&
            me.get(CoreAnnotations.TextAnnotation.class).equalsIgnoreCase("and")) {
          if (DEBUG) {
            System.err.println("Found number and:" + me.word());
          }
          String wd = prev.word();
          if (wd.equalsIgnoreCase("hundred") ||
              wd.equalsIgnoreCase("thousand") ||
              wd.equalsIgnoreCase("million") ||
              wd.equalsIgnoreCase("billion") ||
              wd.equalsIgnoreCase("trillion")) {
            me.set(CoreAnnotations.AnswerAnnotation.class, "NUMBER");
          }
        }
      } else if (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class) != null &&
          (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("NN") ||
              me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("NNS") ||
              me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("NNP"))) {
        if (CURRENCY_WORD_PATTERN.matcher(me.word()).matches()) {
          if (prev.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CD") &&
              prev.getString(CoreAnnotations.AnswerAnnotation.class).equals("NUMBER")) {
            me.set(CoreAnnotations.AnswerAnnotation.class, "MONEY");

            for (int j = i - 1; j >= 0; j--) {
              CoreLabel prev2 = pl.get(j);
              if (prev2.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("CD") &&
                  prev2.getString(CoreAnnotations.AnswerAnnotation.class).equals("NUMBER"))
                prev2.set(CoreAnnotations.AnswerAnnotation.class, "MONEY");
              else
                break;
            }
          }
        } else if (ORDINAL_PATTERN.matcher(me.word()).matches()) {
          if ((next.word() != null && MONTH_PATTERN.matcher(next.word()).matches()) ||
              (next.word() != null && next.word().equalsIgnoreCase("of") &&
                  next2.word() != null && MONTH_PATTERN.matcher(next2.word()).matches())) {
            me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
          }
        }
        if (PART_OF_DAY_PATTERN.matcher(me.word()).matches()) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
        }
        if (ORDINAL_SUFFIX_PATTERN.matcher(me.word()).matches()) {
          if ("NUMBER".equals(prev.get(CoreAnnotations.AnswerAnnotation.class))) {
            me.set(CoreAnnotations.AnswerAnnotation.class, "ORDINAL");
            prev.set(CoreAnnotations.AnswerAnnotation.class, "ORDINAL");
          } else if ("DATE".equals(prev.get(CoreAnnotations.AnswerAnnotation.class))) {
            me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
          }
        }
      } else if (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("JJ")) {
        if ((next.word() != null && MONTH_PATTERN.matcher(next.word()).matches()) ||
            next.word() != null && next.word().equalsIgnoreCase("of") &&
                next2.word() != null && MONTH_PATTERN.matcher(next2.word()).matches()) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        } else if (prev.word() != null && MONTH_PATTERN.matcher(prev.word()).matches()) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        } else if (ORDINAL_PATTERN.matcher(me.word()).matches()) {
          // don't do other tags: don't want 'second' as noun, or 'first' as adverb
          // introducing reasons
          me.set(CoreAnnotations.AnswerAnnotation.class, "ORDINAL");
        }
      } else if (me.getString(CoreAnnotations.PartOfSpeechAnnotation.class).equals("IN") &&
          me.word().equalsIgnoreCase("of")) {
        if (prev.get(CoreAnnotations.TextAnnotation.class) != null &&
            ORDINAL_PATTERN.matcher(prev.get(CoreAnnotations.TextAnnotation.class)).matches() &&
            next.get(CoreAnnotations.TextAnnotation.class) != null &&
            MONTH_PATTERN.matcher(next.get(CoreAnnotations.TextAnnotation.class)).matches()) {
          me.set(CoreAnnotations.AnswerAnnotation.class, "DATE");
        }
      } else if ("o'clock".equals(me.word()) && "CD".equals(prev.getString(CoreAnnotations.PartOfSpeechAnnotation.class))) {
        me.set(CoreAnnotations.AnswerAnnotation.class, "TIME");
      }
    }
    return document;
  }

  // Implement other methods of AbstractSequenceClassifier interface

  @Override
  public void train(Collection<List<CoreLabel>> docs,
      DocumentReaderAndWriter<CoreLabel> readerAndWriter) {
  }

  @Override
  public void serializeClassifier(String serializePath) {
    System.err.print("Serializing classifier to " + serializePath + "...");
    System.err.println("done.");
  }

  @Override
  public void serializeClassifier(ObjectOutputStream oos) {
  }

  @Override
  public void loadClassifier(ObjectInputStream in, Properties props)
      throws IOException, ClassCastException, ClassNotFoundException {
  }

  @Override
  public List<CoreLabel> classifyWithGlobalInformation(List<CoreLabel> tokenSequence, CoreMap document,
      CoreMap sentence) {
    return this.classify(tokenSequence);
  }

}