package org.otel.agent.expr.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.expr.ExpressionCompileException;
import org.otel.agent.expr.parser.Lexer.Token;

class LexerTest {

  private final Lexer lexer = new Lexer();

  private static Token tok(final Token.Type type, final String text, final int position) {
    return new Token(type, text, position);
  }

  private List<Token> tokenize(final String input) throws ExpressionCompileException {
    final List<Token> tokens = lexer.tokenize(input);
    // Last token is always EOF
    assertEquals(Token.Type.EOF, tokens.getLast().type());
    return tokens;
  }

  private Token first(final String input) throws ExpressionCompileException {
    return tokenize(input).getFirst();
  }

  // --- Identifiers ---

  @Test
  void identifierToken() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.IDENTIFIER, "brand", 0), first("brand"));
  }

  @Test
  void identifierStartingWithLetter() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.IDENTIFIER, "_foo123", 0), first("_foo123"));
  }

  // --- Dollar roots ---

  @Test
  void dollarThisRoot() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.DOLLAR_ROOT, "$this", 0), first("$this"));
  }

  @Test
  void dollarArgRoot() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.DOLLAR_ROOT, "$arg0", 0), first("$arg0"));
  }

  @Test
  void dollarArgWithLargeIndex() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.DOLLAR_ROOT, "$arg127", 0), first("$arg127"));
  }

  @Test
  void dollarReturnRoot() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.DOLLAR_ROOT, "$return", 0), first("$return"));
  }

  // --- String literals ---

  @Test
  void singleQuotedString() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.STRING_LITERAL, "BMW", 0), first("'BMW'"));
  }

  @Test
  void doubleQuotedString() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.STRING_LITERAL, "BMW", 0), first("\"BMW\""));
  }

  @Test
  void emptyString() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.STRING_LITERAL, "", 0), first("''"));
  }

  @Test
  void stringWithSpaces() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.STRING_LITERAL, "a b c", 0), first("'a b c'"));
  }

  // --- Numeric literals ---

  @Test
  void integerLiteral() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.NUMBER_LITERAL, "42", 0), first("42"));
  }

  @Test
  void longLiteral() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.NUMBER_LITERAL, "50000", 0), first("50000"));
  }

  @Test
  void decimalLiteral() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.NUMBER_LITERAL, "3.14", 0), first("3.14"));
  }

  @Test
  void negativeNumber() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.NUMBER_LITERAL, "-5", 0), first("-5"));
  }

  // --- Boolean and null literals ---

  @Test
  void trueLiteral() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.TRUE, "true", 0), first("true"));
  }

  @Test
  void falseLiteral() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.FALSE, "false", 0), first("false"));
  }

  @Test
  void nullLiteral() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.NULL, "null", 0), first("null"));
  }

  // --- Operators ---

  @Test
  void andOrNotLexAsIdentifiers() throws ExpressionCompileException {
    // Word operators are contextual keywords: they lex as plain IDENTIFIER tokens.
    assertEquals(tok(Token.Type.IDENTIFIER, "and", 0), first("and"));
    assertEquals(tok(Token.Type.IDENTIFIER, "or", 0), first("or"));
    assertEquals(tok(Token.Type.IDENTIFIER, "not", 0), first("not"));
    assertEquals(tok(Token.Type.IDENTIFIER, "lt", 0), first("lt"));
    assertEquals(tok(Token.Type.IDENTIFIER, "lte", 0), first("lte"));
    assertEquals(tok(Token.Type.IDENTIFIER, "gt", 0), first("gt"));
    assertEquals(tok(Token.Type.IDENTIFIER, "gte", 0), first("gte"));
  }

  @Test
  void keywordPrefixDoesNotMakeKeyword() throws ExpressionCompileException {
    // Greedy identifier scan: notable is a single IDENT, not the `not` keyword.
    assertEquals(tok(Token.Type.IDENTIFIER, "notable", 0), first("notable"));
    assertEquals(tok(Token.Type.IDENTIFIER, "android", 0), first("android"));
    assertEquals(tok(Token.Type.IDENTIFIER, "other", 0), first("other"));
  }

  @Test
  void eqOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.EQ, "==", 0), first("=="));
  }

  @Test
  void neOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.NE, "!=", 0), first("!="));
  }

  @Test
  void inKeyword() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.IN, "in", 0), first("in"));
  }

  @Test
  void plusOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.PLUS, "+", 0), first("+"));
  }

  @Test
  void minusOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.MINUS, "-", 0), first("-"));
  }

  @Test
  void starOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.STAR, "*", 0), first("*"));
  }

  @Test
  void slashOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.SLASH, "/", 0), first("/"));
  }

  @Test
  void percentOperator() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.PERCENT, "%", 0), first("%"));
  }

  // --- Punctuation ---

  @Test
  void lparen() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.LPAREN, "(", 0), first("("));
  }

  @Test
  void rparen() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.RPAREN, ")", 0), first(")"));
  }

  @Test
  void lbracket() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.LBRACKET, "[", 0), first("["));
  }

  @Test
  void rbracket() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.RBRACKET, "]", 0), first("]"));
  }

  @Test
  void comma() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.COMMA, ",", 0), first(","));
  }

  @Test
  void dot() throws ExpressionCompileException {
    assertEquals(tok(Token.Type.DOT, ".", 0), first("."));
  }

  // --- Combined expression ---

  @Test
  void fullExpressionTokenSequence() throws ExpressionCompileException {
    final List<Token> tokens = tokenize("$arg0.brand == 'BMW'");
    assertEquals(6, tokens.size(), "$arg0.brand == 'BMW' should produce 5 tokens + EOF");
    assertEquals(tok(Token.Type.DOLLAR_ROOT, "$arg0", 0), tokens.get(0));
    assertEquals(tok(Token.Type.DOT, ".", 5), tokens.get(1));
    assertEquals(tok(Token.Type.IDENTIFIER, "brand", 6), tokens.get(2));
    assertEquals(tok(Token.Type.EQ, "==", 12), tokens.get(3));
    assertEquals(tok(Token.Type.STRING_LITERAL, "BMW", 15), tokens.get(4));
    assertEquals(tok(Token.Type.EOF, "", 20), tokens.get(5));
  }

  @Test
  void andOrExpression() throws ExpressionCompileException {
    final List<Token> tokens = tokenize("(a or b) and c");
    assertEquals(8, tokens.size());
    assertEquals(Token.Type.LPAREN, tokens.get(0).type());
    assertEquals(Token.Type.IDENTIFIER, tokens.get(1).type());
    assertEquals(Token.Type.IDENTIFIER, tokens.get(2).type());
    assertEquals(Token.Type.IDENTIFIER, tokens.get(3).type());
    assertEquals(Token.Type.RPAREN, tokens.get(4).type());
    assertEquals(Token.Type.IDENTIFIER, tokens.get(5).type());
    assertEquals(Token.Type.IDENTIFIER, tokens.get(6).type());
  }

  // --- Whitespace ---

  @Test
  void whitespaceIsSkipped() throws ExpressionCompileException {
    final List<Token> tokens = tokenize("  $this  ");
    assertEquals(2, tokens.size());
    assertEquals(tok(Token.Type.DOLLAR_ROOT, "$this", 2), tokens.get(0));
  }

  // --- Error cases ---

  @Test
  void unknownPunctuationAtSign() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("@"));
  }

  @Test
  void unknownPunctuationTilde() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("~"));
  }

  // --- Removed symbolic operators are rejected at the lexer ---

  @Test
  void symbolicDoubleAmpersandRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("&&"));
  }

  @Test
  void symbolicDoublePipeRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("||"));
  }

  @Test
  void symbolicSingleBangRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("!"));
  }

  @Test
  void symbolicLtRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("<"));
  }

  @Test
  void symbolicGtRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize(">"));
  }

  @Test
  void symbolicLeRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("<="));
  }

  @Test
  void symbolicGeRejected() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize(">="));
  }

  @Test
  void unclosedStringLiteral() {
    assertThrows(ExpressionCompileException.class, () -> lexer.tokenize("'BMW"));
  }

  @Test
  void emptyInputProducesEofOnly() {
    assertDoesNotThrow(() -> {
      final List<Token> tokens = lexer.tokenize("");
      assertEquals(1, tokens.size());
      assertEquals(Token.Type.EOF, tokens.getFirst().type());
    });
  }
}