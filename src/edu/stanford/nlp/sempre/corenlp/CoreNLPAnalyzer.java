package edu.stanford.nlp.sempre.corenlp;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.SempreUtils;
import edu.stanford.nlp.time.TimeAnnotations;
import edu.stanford.nlp.time.Timex;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Utils;

/**
 * CoreNLPAnalyzer uses Stanford CoreNLP pipeline to analyze an input string utterance
 * and return a LanguageInfo object
 *
 * @author akchou
 */
public class CoreNLPAnalyzer extends LanguageAnalyzer {
  public static class Options {
    // Observe that we run almost everything twice
    // This is because NER and quote_ner have to run before spellcheck (so that spellcheck
    // doesn't try to fix names and quotes), but we want to rerun lemmatization
    // after spellcheck so that new spaces and slash-splitting that spellcheck does
    // are reflected in the lemma tokens and POS tags
    @Option(gloss = "What CoreNLP annotators to run")
    public List<String> annotators = Lists.newArrayList("tokenize", "quote2", "ssplit", "pos", "lemma",
        "ner", "regexner", "quote_ner", "spellcheck", "ssplit", "pos", "lemma");

    @Option(gloss = "Whether to use case-sensitive models")
    public boolean caseSensitive = false;

    @Option(gloss = "What language to use (as a two letter tag)")
    public String languageTag = "en";

    @Option(gloss = "Additional named entity recognizers to run")
    public List<String> entityRecognizers = new ArrayList<>();

    @Option(gloss = "Additional regular expressions to apply to tokens")
    public List<String> regularExpressions = new ArrayList<>();

    @Option(gloss = "Ignore DATE tags on years (numbers between 1000 and 3000) and parse them as numbers")
    public boolean yearsAsNumbers = false;

    @Option(gloss = "Whether to split hyphens or not")
    public boolean splitHyphens = true;
  }

  public static Options opts = new Options();

  // TODO(pliang): don't muck with the POS tag; instead have a separate flag
  // for isContent which looks at posTag != "MD" && lemma != "be" && lemma !=
  // "have"
  // Need to update TextToTextMatcher
  private static final String[] AUX_VERB_ARR = new String[] {"is", "are", "was",
      "were", "am", "be", "been", "will", "shall", "have", "has", "had",
      "would", "could", "should", "do", "does", "did", "can", "may", "might",
      "must", "seem" };
  private static final Set<String> AUX_VERBS = new HashSet<>(Arrays.asList(AUX_VERB_ARR));
  private static final String AUX_VERB_TAG = "VBD-AUX";

  private static final Pattern INTEGER_PATTERN = Pattern.compile("[0-9]{4}");

  private final String languageTag;
  private final StanfordCoreNLP pipeline;
  private final NamedEntityRecognizer[] extraRecognizers;

  public CoreNLPAnalyzer() {
    this(opts.languageTag);
  }

  public CoreNLPAnalyzer(String languageTag) {
    this.languageTag = languageTag;

    Properties props = new Properties();
    
    switch (languageTag) {
    case "en":
    case "en_US":
      if (opts.caseSensitive) {
        props.put("pos.model",
            "edu/stanford/nlp/models/pos-tagger/english-left3words/english-left3words-distsim.tagger");
        props.put("ner.model",
            "edu/stanford/nlp/models/ner/english.all.3class.distsim.crf.ser.gz,edu/stanford/nlp/models/ner/english.conll.4class.distsim.crf.ser.gz");
      } else {
        props.put("pos.model", "edu/stanford/nlp/models/pos-tagger/english-caseless-left3words-distsim.tagger");
        props.put("ner.model",
            "edu/stanford/nlp/models/ner/english.all.3class.caseless.distsim.crf.ser.gz,edu/stanford/nlp/models/ner/english.conll.4class.caseless.distsim.crf.ser.gz");
      }
      break;

    case "de":
      loadResource("StanfordCoreNLP-german.properties", props);
      if (!opts.caseSensitive) {
        props.put("pos.model", "edu/stanford/nlp/models/pos-tagger/german/german-fast-caseless.tagger");
      }
      break;

    case "fr":
      loadResource("StanfordCoreNLP-french.properties", props);
      break;

    case "zh":
      loadResource("StanfordCoreNLP-chinese.properties", props);
      break;

    case "es":
      loadResource("StanfordCoreNLP-spanish.properties", props);
      break;

    default:
      LogInfo.logs("Unrecognized language %s, analysis will not work!", languageTag);
    }

    String annotators = Joiner.on(',').join(opts.annotators);
    props.put("annotators", annotators);

    // disable ssplit (even though we need it to run the rest of the annotators)
    props.put("ssplit.isOneSentence", "true");

    // disable all the builtin numeric classifiers, we have our own
    props.put("ner.applyNumericClassifiers", "false");
    props.put("ner.useSUTime", "false");

    // enable regexner
    props.put("regexner.mapping", "./data/regexner_gazette");
    props.put("regexner.ignoreCase", "true");

    // move quotes to a NER tag
    props.put("customAnnotatorClass.quote2", QuotedStringAnnotator.class.getCanonicalName());
    props.put("customAnnotatorClass.quote_ner", QuotedStringEntityAnnotator.class.getCanonicalName());

    // enable spell checking with our custom annotator
    props.put("customAnnotatorClass.spellcheck", SpellCheckerAnnotator.class.getCanonicalName());
    props.put("spellcheck.dictPath", languageTag);

    // ask for binary tree parses
    props.put("parse.binaryTrees", "true");

    pipeline = new StanfordCoreNLP(props);

    extraRecognizers = new NamedEntityRecognizer[opts.entityRecognizers.size() + opts.regularExpressions.size()];
    for (int i = 0; i < opts.entityRecognizers.size(); i++)
      extraRecognizers[i] = (NamedEntityRecognizer) Utils
          .newInstanceHard(SempreUtils.resolveClassName(opts.entityRecognizers.get(i)));
    for (int i = 0; i < opts.regularExpressions.size(); i++) {
      String spec = opts.regularExpressions.get(i);
      int split = spec.indexOf(':');
      extraRecognizers[opts.entityRecognizers.size() + i] = new RegexpEntityRecognizer(spec.substring(0, split),
          spec.substring(split + 1));
    }
  }

  private static void loadResource(String name, Properties into) {
    try {
      InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
      into.load(stream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // Stanford tokenizer doesn't break hyphens.
  // Replace hypens with spaces for utterances like
  // "Spanish-speaking countries" but not for "2012-03-28".
  // Also not break hyphens with spaces for things like 1-800-GOT-MILK
  public static String breakHyphens(String utterance) {
    StringBuilder buf = new StringBuilder(utterance);

    boolean seenHyphen = false;
    for (int i = 1; i < buf.length() - 1; i++) {
      char c = buf.charAt(i);
      if (c == '-') {
        if (!seenHyphen && Character.isLetter(buf.charAt(i - 1)) && Character.isLetter(buf.charAt(i + 1)))
          buf.setCharAt(i, ' ');
        else
          seenHyphen = true;
      } else if (Character.isWhitespace(c))
        seenHyphen = false;
    }
    return buf.toString();
  }

  // recognize two numbers in one token, because CoreNLP's tokenizer will not split them
  private static final Pattern BETWEEN_PATTERN = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)-(-?[0-9]+(?:\\.[0-9]+)?)");

  private void recognizeNumberSequences(List<CoreLabel> words) {
    QuantifiableEntityNormalizer.applySpecializedNER(words);
  }

  @Override
  public LanguageInfo analyze(String utterance) {
    LanguageInfo languageInfo = new LanguageInfo();

    // Clear these so that analyze can hypothetically be called
    // multiple times.
    languageInfo.tokens.clear();
    languageInfo.posTags.clear();
    languageInfo.nerTags.clear();
    languageInfo.nerValues.clear();
    languageInfo.lemmaTokens.clear();

    // Break hyphens
    if (opts.splitHyphens)
      utterance = breakHyphens(utterance);

    utterance = utterance.replaceAll("([0-9])(?!am|pm)([a-zA-Z])", "$1 $2");

    // Run Stanford CoreNLP

    // Work around CoreNLP issue #622
    Annotation annotation = pipeline.process(utterance + " ");

    // run numeric classifiers
    recognizeNumberSequences(annotation.get(CoreAnnotations.TokensAnnotation.class));

    for (CoreLabel token : annotation.get(CoreAnnotations.TokensAnnotation.class)) {
      String word = token.get(TextAnnotation.class);
      String wordLower = word.toLowerCase();
      String nerTag = token.get(NamedEntityTagAnnotation.class);
      if (nerTag == null)
        nerTag = "O";
      String nerValue = token.get(NormalizedNamedEntityTagAnnotation.class);
      if(nerValue == null) {
        Timex nerValue_ = token.get(TimeAnnotations.TimexAnnotation.class);
        if(nerValue_ != null) {
          nerValue = nerValue_.value();
        }
      }
      String posTag = token.get(PartOfSpeechAnnotation.class);

      boolean addComma = false;
      if (word.endsWith(",") && !",".equals(word)) {
        word = word.substring(0, word.length() - 1);
        wordLower = wordLower.substring(0, wordLower.length() - 1);
        addComma = true;
      }

      if (opts.yearsAsNumbers && nerTag.equals("DATE") && INTEGER_PATTERN.matcher(nerValue).matches()) {
        nerTag = "NUMBER";
      }

      if (wordLower.equals("9-11") || wordLower.equals("911")) {
        nerTag = "O";
      } else {
        Matcher twoNumbers = BETWEEN_PATTERN.matcher(wordLower);
        if (twoNumbers.matches()) {
          // CoreNLP does something somewhat dumb when it comes to X-Y when X and Y are both numbers
          // we want to split them and treat them separately
          String num1 = twoNumbers.group(1);
          String num2 = twoNumbers.group(2);

          languageInfo.tokens.add(num1);
          languageInfo.lemmaTokens.add(num1);
          languageInfo.posTags.add("CD");
          languageInfo.nerTags.add("NUMBER");
          languageInfo.nerValues.add(num1);

          languageInfo.tokens.add("-");
          languageInfo.lemmaTokens.add("-");
          languageInfo.posTags.add(":");
          languageInfo.nerTags.add("O");
          languageInfo.nerValues.add(null);

          languageInfo.tokens.add(num2);
          languageInfo.lemmaTokens.add(num2);
          languageInfo.posTags.add("CD");
          languageInfo.nerTags.add("NUMBER");
          languageInfo.nerValues.add(num2);
          continue;
        }
      }

      if (LanguageAnalyzer.opts.lowerCaseTokens) {
        languageInfo.tokens.add(wordLower);
      } else {
        languageInfo.tokens.add(word);
      }
      if (languageTag.equals("en")) {
        languageInfo.posTags.add(AUX_VERBS.contains(wordLower) ? AUX_VERB_TAG : posTag);
      } else {
        languageInfo.posTags.add(token.get(PartOfSpeechAnnotation.class));
      }
      languageInfo.lemmaTokens.add(token.get(LemmaAnnotation.class));

      // if it's not a noun and not an adjective it's not an organization 
      if (!posTag.startsWith("N") && !posTag.startsWith("J") && nerTag.equals("ORGANIZATION"))
        nerTag = "O";

      languageInfo.nerTags.add(nerTag);
      languageInfo.nerValues.add(nerValue);

      if (addComma) {
        languageInfo.tokens.add(",");
        languageInfo.posTags.add(",");
        languageInfo.lemmaTokens.add(",");
        languageInfo.nerTags.add("O");
        languageInfo.nerValues.add(null);
      }
    }

    // fix corenlp's tokenizer being weird around "/"
    boolean inquote = false;
    int n = languageInfo.tokens.size();
    for (int i = 0; i < n; i++) {
      String token = languageInfo.tokens.get(i);
      if ("``".equals(token)) {
        inquote = true;
        continue;
      }
      if ("''".equals(token)) {
        if (inquote) {
          inquote = false;
          continue;
        }
        if (i < n - 2 && languageInfo.tokens.get(i + 1).equals("/") && languageInfo.tokens.get(i + 2).equals("''")) {
          languageInfo.tokens.set(i, "``");
          inquote = true;
        }
      }
    }

    // fix corenlp sometimes tagging Washington as location in "washington post"
    for (int i = 0; i < n - 1; i++) {
      String token = languageInfo.tokens.get(i);
      String next = languageInfo.tokens.get(i + 1);

      if (!("O".equals(languageInfo.nerTags.get(i)) ||
          "ORGANIZATION".equals(languageInfo.nerTags.get(i)) ||
          "LOCATION".equals(languageInfo.nerTags.get(i))))
        continue;

      if (("washington".equals(token) && ("post".equals(next) || "posts".equals(next))) ||
          ("chicago".equals(token) && "cubs".equals(next)) ||
          ("toronto".equals(token) && "fc".equals(next))) {
        languageInfo.nerTags.set(i, "ORGANIZATION");
        languageInfo.nerValues.set(i, null);

        languageInfo.nerTags.set(i + 1, "ORGANIZATION");
        languageInfo.nerValues.set(i + 1, null);
      }

      if ("us".equals(token) && "business".equals(next)) {
        languageInfo.nerTags.set(i, "O");
        languageInfo.nerValues.set(i, null);
      }

      // apple post is not a f... newspaper
      // stupid corenlp
      if ("apple".equals(token) && "post".equals(next)) {
        languageInfo.nerTags.set(i + 1, "O");
        languageInfo.nerValues.set(i + 1, null);
      }

      // topic is not an organization
      if ("topic".equals(token)) {
        languageInfo.nerTags.set(i, "O");
        languageInfo.nerValues.set(i, null);
      }

      // merge locations separated by a comma (eg Palo Alto, California)
      if (",".equals(token) && i >0 && "LOCATION".equals(languageInfo.nerTags.get(i-1))
          && "LOCATION".equals(languageInfo.nerTags.get(i+1))) {
        languageInfo.nerTags.set(i, "LOCATION");
      }
    }

    // Run additional entity recognizers
    for (NamedEntityRecognizer r : extraRecognizers)
      r.recognize(languageInfo);

    languageInfo.computeNerTokens();
    return languageInfo;
  }

  // Test on example sentence.
  public static void main(String[] args) {
    CoreNLPAnalyzer.opts.entityRecognizers = Lists.newArrayList("corenlp.PhoneNumberEntityRecognizer",
        "corenlp.EmailEntityRecognizer", "corenlp.URLEntityRecognizer");
    CoreNLPAnalyzer.opts.regularExpressions = Lists.newArrayList("USERNAME:[@](.+)", "HASHTAG:[#](.+)");
    CoreNLPAnalyzer.opts.yearsAsNumbers = true;
    CoreNLPAnalyzer.opts.splitHyphens = false;
    CoreNLPAnalyzer analyzer = new CoreNLPAnalyzer();

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
      while (true) {
        System.out.println("Enter some text:");
        String text = reader.readLine();
        if (text == null)
          break;
        LanguageInfo langInfo = analyzer.analyze(text);
        LogInfo.begin_track("Analyzing \"%s\"", text);
        LogInfo.logs("tokens: %s", langInfo.tokens);
        LogInfo.logs("lemmaTokens: %s", langInfo.lemmaTokens);
        LogInfo.logs("posTags: %s", langInfo.posTags);
        LogInfo.logs("nerTags: %s", langInfo.nerTags);
        LogInfo.logs("nerValues: %s", langInfo.nerValues);
        LogInfo.end_track();
      }
    } catch (IOException e) {
        e.printStackTrace();
    }
  }
}
