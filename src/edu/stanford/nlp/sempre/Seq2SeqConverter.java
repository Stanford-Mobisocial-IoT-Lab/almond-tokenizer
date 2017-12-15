package edu.stanford.nlp.sempre;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;

class Seq2SeqConverter {
  public static void writeSequences(int id, List<List<String>> sequences, Writer writer) throws IOException {
    writer.append(Integer.toString(id));
    writer.append('\t');
    boolean firstSequence = true;
    for (List<String> sequence : sequences) {
      if (!firstSequence)
        writer.append('\t');
      firstSequence = false;

      boolean first = true;
      for (String t : sequence) {
        if (!first)
          writer.append(' ');
        first = false;
        writer.append(t);
      }
    }
    writer.append('\n');
  }

  private final Seq2SeqTokenizer tokenizer;

  private Example ex;
  private Map<Seq2SeqTokenizer.Value, List<Integer>> entities;
  private final List<String> outputTokens = new ArrayList<>();

  public Seq2SeqConverter(String languageTag) {
    tokenizer = new Seq2SeqTokenizer(languageTag, true);
  }

  public List<List<String>> run(Example ex) {
    this.ex = ex;
    outputTokens.clear();

    Seq2SeqTokenizer.Result tokenizerResult = tokenizer.process(ex);
    entities = tokenizerResult.entities;

    writeOutput();

    for (Map.Entry<Seq2SeqTokenizer.Value, List<Integer>> entry : entities.entrySet()) {
      if (entry.getValue().isEmpty())
        continue;

      String entityType = entry.getKey().type;
      Object entityValue = entry.getKey().value;
      for (int id : entry.getValue()) {
        System.out.println(ex.id + ": unused entity " + entityType + "_" + id + " (" + entityValue + ")");
      }
    }

    return Arrays.asList(Collections.unmodifiableList(tokenizerResult.tokens),
        Collections.unmodifiableList(outputTokens), Collections.unmodifiableList(tokenizerResult.constituencyParse));
  }

  private void writeOutput() {
    try {
      Map<?, ?> json = Json.getMapper().readerWithView(Object.class).withType(Map.class)
          .readValue(ex.targetJson);

      if (json.containsKey("special"))
        writeSpecial((Map<?, ?>) json.get("special"));
      else if (json.containsKey("answer"))
        writeAnswer((Map<?, ?>) json.get("answer"));
      else if (json.containsKey("command"))
        writeCommand((Map<?, ?>) json.get("command"));
      else if (json.containsKey("rule") || json.containsKey("trigger") || json.containsKey("query")
          || json.containsKey("action"))
        writeProgram(json);
      else if (json.containsKey("access"))
        writePolicy(json);
      else if (json.containsKey("setup"))
        writeRemoteProgram(json);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Example " + ex.id + " does not parse as JSON", e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void writeProgram(Map<?, ?> json) {
    outputTokens.add("rule");
    writeThingTalkish(json);
  }

  private void writeRemoteProgram(Map<?, ?> json) {
    outputTokens.add("setup");
    Map<?, ?> setup = (Map<?, ?>) json.get("setup");
    writeValue("USERNAME", setup.get("person"));
    writeThingTalkish(setup);
  }

  private void writePolicy(Map<?, ?> json) {
    outputTokens.add("policy");
    Map<?, ?> access = (Map<?, ?>) json.get("access");
    if (access.containsKey("person"))
      writeValue("USERNAME", access.get("person"));
    writeThingTalkish(access);
  }

  private void writeThingTalkish(Map<?, ?> json) {
    if (json.containsKey("rule"))
      writeRule((Map<?, ?>) json.get("rule"));
    else if (json.containsKey("trigger"))
      writeTopInvocation("trigger", (Map<?, ?>) json.get("trigger"));
    else if (json.containsKey("query"))
      writeTopInvocation("query", (Map<?, ?>) json.get("query"));
    else if (json.containsKey("action"))
      writeTopInvocation("action", (Map<?, ?>) json.get("action"));
  }

  private void writeSpecial(Map<?, ?> special) {
    outputTokens.add("bookkeeping");
    outputTokens.add("special");
    String id = (String) special.get("id");
    outputTokens.add(id);
  }

  private void writeTopInvocation(String invocationType, Map<?, ?> map) {
    switch (invocationType) {
    case "trigger":
      break;
    case "query":
      outputTokens.add("tt:$builtin.now");
      break;
    case "action":
      outputTokens.add("tt:$builtin.now");
      outputTokens.add("tt:$builtin.noop");
      break;
    }
    writeInvocation(invocationType, map);
    switch (invocationType) {
    case "trigger":
      outputTokens.add("tt:$builtin.noop");
      outputTokens.add("tt:$builtin.notify");
      break;
    case "query":
      outputTokens.add("tt:$builtin.notify");
      break;
    }
  }

  private void writeInvocation(String invocationType, Map<?, ?> invocation) {
    Map<?, ?> name = (Map<?, ?>) invocation.get("name");
    outputTokens.add(name.get("id").toString());

    if (invocation.containsKey("person"))
      writeValue("USERNAME", invocation.get("person"));

    List<?> arguments = (List<?>) invocation.get("args");
    for (Object o : arguments) {
      Map<?, ?> arg = (Map<?, ?>) o;
      Map<?, ?> argName = (Map<?, ?>) arg.get("name");
      outputTokens.add(argName.get("id").toString().replace("tt:param.", "tt-param:"));
      //outputTokens.add(arg.get("operator").toString());
      writeArgument(arg);
    }
    
    List<?> predicate = (List<?>) invocation.get("predicate");
    if (predicate == null)
      predicate = Collections.emptyList();
    
    boolean firstAnd = true;

    for (Object o : predicate) {
      if (firstAnd)
        outputTokens.add("if");
      else
        outputTokens.add("and");
      firstAnd = false;

      List<?> orPredicate = (List<?>) o;

      boolean firstOr = true;

      for (Object o2 : orPredicate) {
        Map<?, ?> filter = (Map<?, ?>) o2;

        if (!firstOr)
          outputTokens.add("or");
        firstOr = false;

        Map<?, ?> argName = (Map<?, ?>) filter.get("name");
        outputTokens.add(argName.get("id").toString().replace("tt:param.", "tt-param:"));
        outputTokens.add(filter.get("operator").toString());
        writeArgument(filter);
      }
    }
  }

  private void writeRule(Map<?, ?> rule) {
    if (rule.containsKey("trigger")) {
      //outputTokens.add("if");
      writeInvocation("trigger", (Map<?, ?>) rule.get("trigger"));
    } else {
      outputTokens.add("tt:$builtin.now");
    }
    if (rule.containsKey("query")) {
      writeInvocation("query", (Map<?, ?>) rule.get("query"));
    } else {
      outputTokens.add("tt:$builtin.noop");
    }
    if (rule.containsKey("action")) {
      writeInvocation("action", (Map<?, ?>) rule.get("action"));
    } else {
      outputTokens.add("tt:$builtin.notify");
    }
  }

  private void writeCommand(Map<?, ?> command) {
    outputTokens.add("bookkeeping");
    outputTokens.add("command");
    outputTokens.add((String) command.get("type"));

    Map<?, ?> value = (Map<?, ?>) command.get("value");

    String valueStr;
    if (value.containsKey("value"))
      valueStr = value.get("value").toString();
    else if (value.containsKey("name"))
      valueStr = value.get("name").toString();
    else
      valueStr = value.get("id").toString();
    if (!valueStr.equals("generic"))
      valueStr = "tt-device:" + valueStr;
    outputTokens.add(valueStr);
  }

  private void writeAnswer(Map<?, ?> answer) {
    outputTokens.add("bookkeeping");
    outputTokens.add("answer");
    writeArgument(answer);
  }

  private boolean writeValue(String type, Object value, boolean warn) {
    Seq2SeqTokenizer.Value searchKey = new Seq2SeqTokenizer.Value(type, value);
    List<Integer> ids = entities.getOrDefault(searchKey, Collections.emptyList());

    if (ids.isEmpty()) {
      if (warn) {
        // try again with QUOTED_STRING

        switch (type) {
        case "QUOTED_STRING":
          if (writeValue("USERNAME", value, false))
            return true;
          if (writeValue("HASHTAG", value, false))
            return true;
          break;
        case "USERNAME":
          if (writeValue("QUOTED_STRING", "@" + value, false))
            return true;
          break;
        case "HASHTAG":
          if (writeValue("QUOTED_STRING", "#" + value, false))
            return true;
          break;
        case "PHONE_NUMBER":
        case "EMAIL_ADDRESS":
        case "URL":
          if (writeValue("QUOTED_STRING", value, false))
            return true;
          break;
        }

        // one is implicit in expressions like "every hour" (= every 1 h) or "every week" (= every 1 week)
        // at same time, zero is implicit in expressions like "no X" or "none"
        // previously we tried to use CoreNLP's SET handling, but that was unreliable
        // instead, we just add a new token that the NN can learn to predict
        if (type.equals("NUMBER")) {
          Number num = ((Number) value);
          if (num.intValue() == 1) {
            outputTokens.add("1");
            return true;
          }
          if (num.intValue() == 0) {
            outputTokens.add("0");
            return true;
          }
          // fallthrough and warn
        }

        System.out
            .println(ex.id + ": cannot find value " + type + " " + value + ", have "
                + entities);
        // write the type and hope for the best
        outputTokens.add(type + "_0");
      }
      return false;
    }

    int id = ids.remove(0);

    outputTokens.add(type + "_" + id);
    return true;
  }

  private void writeValue(String type, Object value) {
    writeValue(type, value, true);
  }

  private void writeArgument(Map<?, ?> argument) {
    String type = (String) argument.get("type");
    Map<?, ?> value = (Map<?, ?>) argument.get("value");
    if (type.startsWith("Entity(")) {
      switch (type) {
      case "Entity(tt:device)":
        outputTokens.add("tt-device:" + value.get("value").toString());
        break;
      case "Entity(tt:function)":
        outputTokens.add("tt-function:" + value.get("value").toString());
        break;
      case "Entity(tt:contact_name)":
        writeValue("USERNAME", value.get("value").toString());
        break;
      default:
        writeValue("GENERIC_ENTITY_" + type.substring("Entity(".length(), type.length() - 1),
            new EntityValue(type, value.get("value").toString()));
      }
      return;
    }
    switch (type) {
    case "Location":
      String relativeTag = (String) value.get("relativeTag");
      if (relativeTag.equals("absolute")) {
        double latitude = ((Number) value.get("latitude")).doubleValue();
        double longitude = ((Number) value.get("longitude")).doubleValue();
        LocationValue loc = new LocationValue(latitude, longitude);
        writeValue("LOCATION", loc);
      } else {
        outputTokens.add(relativeTag);
      }
      break;

    case "Boolean":
    case "Bool":
    case "Enum":
      outputTokens.add(value.get("value").toString());
      break;

    case "VarRef":
      outputTokens.add(value.get("id").toString().replace("tt:param.", "tt-param:"));
      break;

    case "String":
      writeValue("QUOTED_STRING", value.get("value").toString());
      break;

    case "Date":
      writeValue("DATE",
          new DateValue((Integer) value.get("year"), (Integer) value.get("month"), (Integer) value.get("day"),
              value.containsKey("hour") ? (int) (Integer) value.get("hour") : 0,
              value.containsKey("minute") ? (int) (Integer) value.get("minute") : 0,
              value.containsKey("second") ? (int) ((Number) value.get("second")).doubleValue() : 0));
      break;

    case "Time":
      writeValue("TIME",
          new TimeValue((Integer) value.get("hour"), (Integer) value.get("minute")));
      break;

    case "Username":
      writeValue("USERNAME", value.get("value").toString());
      break;

    case "Hashtag":
      writeValue("HASHTAG", value.get("value").toString());
      break;

    case "Number":
      writeValue("NUMBER", ((Number) value.get("value")).doubleValue());
      break;

    case "Measure":
      if (UnitHelpers.isTimeUnit(value.get("unit").toString())) {
        NumberValue numValue = new NumberValue(((Number) value.get("value")).doubleValue(),
            value.get("unit").toString());
        if (writeValue("DURATION", numValue, false))
          break;
      }
      writeValue("NUMBER", ((Number) value.get("value")).doubleValue());
      outputTokens.add(value.get("unit").toString());
      break;

    case "PhoneNumber":
      writeValue("PHONE_NUMBER", value.get("value").toString());
      break;

    case "EmailAddress":
      writeValue("EMAIL_ADDRESS", value.get("value").toString());
      break;

    case "URL":
      writeValue("URL", value.get("value").toString());
      break;

    default:
      throw new IllegalArgumentException("Invalid value type " + type);
    }
  }
}
