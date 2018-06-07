package edu.stanford.nlp.sempre;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import com.google.common.collect.Sets;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.Annotator;
import edu.stanford.nlp.sempre.QuotedStringAnnotator.QuoteAnnotation;
import edu.stanford.nlp.util.ArraySet;
import edu.stanford.nlp.util.logging.Redwood;

public class SpellCheckerAnnotator implements Annotator {
  private static final Redwood.RedwoodChannels log = Redwood.channels(SpellCheckerAnnotator.class);

  private final HunspellDictionary dictionary;
  private final boolean enabled;

  private static final Pattern NUMERIC_PATTERN = Pattern.compile("[-+0-9:/.]+.*");
  private static final Pattern BLANK_PATTERN = Pattern.compile("_+");
  private static final Set<String> PTB_PUNCTUATION = Sets.newHashSet("-lrb-", "-lsb-", "-rrb-", "-rsb-", "'", "`", "''",
      "``", ",", "-LRB-", "-RRB-", "%", ">", "<", "-LSB-", "-RSB-");
  private static final Pattern FILENAME_PATTERN = Pattern.compile(".*\\.[a-zA-Z0-9]{2,4}");

  private final Set<String> extraDictionary = new HashSet<>();
  private final Map<String, String> replacements = new HashMap<>();

  public SpellCheckerAnnotator(String name, Properties props) {
    this(props.getProperty(name + ".dictPath", "en_US"),
        props.getProperty(name + ".extraDictionary", "./data/dictionary"),
        props.getProperty(name + ".extraReplacements", "./data/replacements"),
        props.getProperty(name + ".dictionaryDirectory", "/usr/share/myspell"));
  }

  private SpellCheckerAnnotator(String languageTag,
      String extraDictionaryPath,
      String extraReplacementsPath,
      String dictionaryDirectory) {
    switch (languageTag) {
    case "en":
      languageTag = "en_US";
      break;
    case "it":
      languageTag = "it_IT";
      break;
    case "zh":
      enabled = false;
      dictionary = null;
      return;

    default:
      // don't add a country, and hope for the best  
    }
    enabled = true;

    try {
      dictionary = new HunspellDictionary(dictionaryDirectory + "/" + languageTag);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    for (String line : IOUtils.readLines(extraDictionaryPath)) {
      extraDictionary.add(line.trim());
    }
    
    for (String line : IOUtils.readLines(extraReplacementsPath)) {
      line = line.trim();
      if (line.startsWith("#") || line.isEmpty())
        continue;
      
      String[] tokens = line.split("\t");
      replacements.put(tokens[0], tokens[1]);
    }
  }

  private boolean slashCompoundWord(CoreLabel token, String word, List<CoreLabel> newTokens) {
    String[] split = word.split("/");
    if (split.length <= 1)
      return false;
    
    for (String splitword : split) {
      if (!dictionary.spell(splitword))
        return false;
    }
    
    String[] lemmaSplit = token.get(CoreAnnotations.LemmaAnnotation.class).split("/");

    int begin = token.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class);
    for (int i = 0; i < split.length; i++) {
      if (i > 0) {
        CoreLabel slashToken = new CoreLabel(token);
        slashToken.set(CoreAnnotations.TextAnnotation.class, "/");
        slashToken.set(CoreAnnotations.LemmaAnnotation.class, "/");
        slashToken.set(CoreAnnotations.CharacterOffsetBeginAnnotation.class, begin);
        slashToken.set(CoreAnnotations.CharacterOffsetEndAnnotation.class, begin + 1);
        begin++;
        newTokens.add(slashToken);
      }
      CoreLabel newToken = new CoreLabel(token);
      newToken.set(CoreAnnotations.TextAnnotation.class, split[i]);
      newToken.set(CoreAnnotations.LemmaAnnotation.class, lemmaSplit[i]);
      newToken.set(CoreAnnotations.CharacterOffsetBeginAnnotation.class, begin);
      newToken.set(CoreAnnotations.CharacterOffsetEndAnnotation.class, begin + split[i].length());
      begin += split[i].length();
      newTokens.add(newToken);
    }

    return true;
  }

  @Override
  public void annotate(Annotation annotation) {
    if (!enabled)
        return;

    List<CoreLabel> tokens = annotation.get(CoreAnnotations.TokensAnnotation.class);

    List<CoreLabel> newTokens = new ArrayList<>();
    for (CoreLabel token : tokens) {
      String word = token.get(CoreAnnotations.TextAnnotation.class);

      if (token.get(QuoteAnnotation.class) != null ||
          PTB_PUNCTUATION.contains(word) ||
          word.contains("@") || word.startsWith("#") || word.contains(".") || word.length() <= 2 ||
          FILENAME_PATTERN.matcher(word).matches() ||
          NUMERIC_PATTERN.matcher(word).matches() ||
          BLANK_PATTERN.matcher(word).matches() ||
          word.startsWith("http") || word.startsWith("www") ||
          (token.ner() != null && !"O".equals(token.ner()))) {
        newTokens.add(token);
        continue;
      }

      if (replacements.containsKey(word.toLowerCase())) {
        doReplace(token, newTokens, word, replacements.get(word.toLowerCase()));
        continue;
      }

      if (extraDictionary.contains(word.toLowerCase()) || dictionary.spell(word)) {
        newTokens.add(token);
        continue;
      }

      if (slashCompoundWord(token, word, newTokens)) {
        continue;
      }

      List<String> replacements = dictionary.suggest(word);
      if (replacements.isEmpty()) {
        log.logf("Found no replacement for mispelled word %s", word);
        newTokens.add(token);
        continue;
      } else {
        doReplace(token, newTokens, word, replacements.get(0));
      }
    }
    assert newTokens.size() >= tokens.size();

    for (int i = 0; i < newTokens.size(); i++) {
      newTokens.get(i).setIndex(i);
    }

    annotation.set(CoreAnnotations.TokensAnnotation.class, newTokens);
  }

  private void doReplace(CoreLabel token, List<CoreLabel> newTokens, String word, String replacement) {
    if (word.equalsIgnoreCase(replacement)) {
      newTokens.add(token);
      return;
    }

    String[] newWords = replacement.split("\\s+");

    for (String newWord : newWords) {
      CoreLabel newToken = new CoreLabel(token);
      newToken.set(CoreAnnotations.TextAnnotation.class, newWord);
      // we're not going to rerun the lemmatizer, so just go with it
      newToken.set(CoreAnnotations.LemmaAnnotation.class, newWord);
      newTokens.add(newToken);
    }
    log.logf("Replaced mispelled word %s as %s", word, replacement);
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
    return Collections.emptySet();
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requires() {
    return Collections.unmodifiableSet(new ArraySet<>(Arrays.asList(
        CoreAnnotations.TextAnnotation.class,
        CoreAnnotations.TokensAnnotation.class,
        CoreAnnotations.PositionAnnotation.class)));
  }

}