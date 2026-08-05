package org.otel.agent.expr.parser;

import java.util.ArrayList;
import java.util.List;
import org.otel.agent.expr.ExpressionCompileException;

/**
 * Hand-written tokenizer for the condition DSL.
 *
 * <p>Performs a single forward scan of the input string, producing a list of {@link Token}
 * records. Unknown characters throw {@link ExpressionCompileException} with the character position.
 */
public final class Lexer {
  public record Token(Token.Type type, String text, int position) {
    public enum Type {
      IDENTIFIER,
      DOLLAR_ROOT,
      STRING_LITERAL,
      NUMBER_LITERAL,
      TRUE,
      FALSE,
      NULL,
      EQ,
      NE,
      IN,
      PLUS,
      MINUS,
      STAR,
      SLASH,
      PERCENT,
      LPAREN,
      RPAREN,
      LBRACKET,
      RBRACKET,
      COMMA,
      DOT,
      EOF
    }
  }

  public List<Token> tokenize(final String input) throws ExpressionCompileException {
    final List<Token> tokens = new ArrayList<>();
    int pos = 0;
    while (pos < input.length()) {
      final char c = input.charAt(pos);
      if (Character.isWhitespace(c)) {
        pos++;
        continue;
      }
      pos = scanToken(input, pos, tokens);
    }
    tokens.add(new Token(Token.Type.EOF, "", pos));
    return tokens;
  }

  private int scanToken(final String input, final int start, final List<Token> tokens)
      throws ExpressionCompileException {
    final char c = input.charAt(start);
    if (c == '$') return scanDollarRoot(input, start, tokens);
    if (c == '\'' || c == '"') return scanStringLiteral(input, start, c, tokens);
    if (Character.isDigit(c)) return scanNumber(input, start, tokens);
    if (c == '-' && start + 1 < input.length() && Character.isDigit(input.charAt(start + 1))) {
      return scanNumber(input, start, tokens);
    }
    if (Character.isLetter(c) || c == '_') return scanIdentifier(input, start, tokens);
    return scanOperatorOrPunctuation(input, start, c, tokens);
  }

  private int scanDollarRoot(final String input, final int start, final List<Token> tokens)
      throws ExpressionCompileException {
    int pos = start + 1;
    while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
      pos++;
    }
    final String text = input.substring(start, pos);
    if (!text.equals("$this") && !text.equals("$return") && !(text.startsWith("$arg") && text.length() > 4)) {
      throw new ExpressionCompileException("?", "syntax error", "invalid root: " + text + " at " + start);
    }
    if (text.startsWith("$arg")) {
      final String digits = text.substring(4);
      for (int i = 0; i < digits.length(); i++) {
        if (!Character.isDigit(digits.charAt(i))) {
          throw new ExpressionCompileException("?", "syntax error", "invalid $arg index: " + text + " at " + start);
        }
      }
    }
    tokens.add(new Token(Token.Type.DOLLAR_ROOT, text, start));
    return pos;
  }

  private int scanStringLiteral(final String input, final int start, final char quote, final List<Token> tokens)
      throws ExpressionCompileException {
    int pos = start + 1;
    while (pos < input.length() && input.charAt(pos) != quote) {
      pos++;
    }
    if (pos >= input.length()) {
      throw new ExpressionCompileException("?", "syntax error", "unclosed string literal at " + start);
    }
    final String text = input.substring(start + 1, pos);
    tokens.add(new Token(Token.Type.STRING_LITERAL, text, start));
    return pos + 1;
  }

  private int scanNumber(final String input, final int start, final List<Token> tokens) {
    int pos = start;
    if (input.charAt(pos) == '-') pos++;
    while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
      pos++;
    }
    if (pos < input.length() && input.charAt(pos) == '.') {
      pos++;
      while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
        pos++;
      }
    }
    final String text = input.substring(start, pos);
    tokens.add(new Token(Token.Type.NUMBER_LITERAL, text, start));
    return pos;
  }

  private int scanIdentifier(final String input, final int start, final List<Token> tokens) {
    int pos = start;
    while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
      pos++;
    }
    final String text = input.substring(start, pos);
    final Token.Type type = switch (text) {
      case "true" -> Token.Type.TRUE;
      case "false" -> Token.Type.FALSE;
      case "null" -> Token.Type.NULL;
      case "in" -> Token.Type.IN;
      default -> Token.Type.IDENTIFIER;
    };
    tokens.add(new Token(type, text, start));
    return pos;
  }

  private int scanOperatorOrPunctuation(final String input, final int start, final char c, final List<Token> tokens)
      throws ExpressionCompileException {
    return switch (c) {
      case '!' -> {
        if (consume(input, start, '=')) {
          tokens.add(new Token(Token.Type.NE, "!=", start));
          yield start + 2;
        }
        throw new ExpressionCompileException("?", "syntax error", "unexpected '!' at " + start);
      }
      case '=' -> {
        if (consume(input, start, '=')) {
          tokens.add(new Token(Token.Type.EQ, "==", start));
          yield start + 2;
        }
        throw new ExpressionCompileException("?", "syntax error", "unexpected '=' at " + start);
      }
      case '+' -> single(tokens, Token.Type.PLUS, "+", start);
      case '-' -> single(tokens, Token.Type.MINUS, "-", start);
      case '*' -> single(tokens, Token.Type.STAR, "*", start);
      case '/' -> single(tokens, Token.Type.SLASH, "/", start);
      case '%' -> single(tokens, Token.Type.PERCENT, "%", start);
      case '(' -> single(tokens, Token.Type.LPAREN, "(", start);
      case ')' -> single(tokens, Token.Type.RPAREN, ")", start);
      case '[' -> single(tokens, Token.Type.LBRACKET, "[", start);
      case ']' -> single(tokens, Token.Type.RBRACKET, "]", start);
      case ',' -> single(tokens, Token.Type.COMMA, ",", start);
      case '.' -> single(tokens, Token.Type.DOT, ".", start);
      default -> throw new ExpressionCompileException("?", "syntax error", "unexpected character '" + c + "' at " + start);
    };
  }

  private boolean consume(final String input, final int pos, final char expected) {
    return pos + 1 < input.length() && input.charAt(pos + 1) == expected;
  }

  private int single(final List<Token> tokens, final Token.Type type, final String text, final int pos) {
    tokens.add(new Token(type, text, pos));
    return pos + 1;
  }
}