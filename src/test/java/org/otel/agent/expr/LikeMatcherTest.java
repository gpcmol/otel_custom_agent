package org.otel.agent.expr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LikeMatcherTest {

  @Nested
  class Like {

    @Test
    void prefixPattern() {
      assertTrue(LikeMatcher.like("BMW-X5", "BMW%"));
    }

    @Test
    void suffixPattern() {
      assertTrue(LikeMatcher.like("Piet", "%iet"));
    }

    @Test
    void containsPattern() {
      assertTrue(LikeMatcher.like("aPiEt\u00d7\u00d7", "%PiEt%"));
    }

    @Test
    void singleCharWildcardMatches() {
      assertTrue(LikeMatcher.like("Piet", "Pi_t"));
    }

    @Test
    void singleCharWildcardRequiresExactlyOneChar() {
      assertFalse(LikeMatcher.like("Pi", "Pi_t"), "_ must match exactly one char, not zero");
    }

    @Test
    void emptyPatternMatchesEmptyString() {
      assertTrue(LikeMatcher.like("", ""));
    }

    @Test
    void emptyPatternDoesNotMatchNonEmptyString() {
      assertFalse(LikeMatcher.like("x", ""));
    }

    @Test
    void patternLongerThanValue() {
      assertFalse(LikeMatcher.like("ab", "abc%"));
    }

    @Test
    void noMatch() {
      assertFalse(LikeMatcher.like("audi", "BMW%"));
    }

    @Test
    void likeIsCaseSensitive() {
      assertFalse(LikeMatcher.like("bmw", "BMW%"));
    }

    @Test
    void exactMatchNoWildcards() {
      assertTrue(LikeMatcher.like("BMW", "BMW"));
    }

    @Test
    void multipleWildcards() {
      assertTrue(LikeMatcher.like("ABCDEF", "A%C_EF"));
    }

    @Test
    void consecutivePercentWildcards() {
      assertTrue(LikeMatcher.like("ABC", "A%%%BC"));
    }

    @Test
    void underscoreWildcardMustMatch() {
      assertFalse(LikeMatcher.like("BC", "A_BC"), "needs at least one char before A_BC");
    }
  }

  @Nested
  class Ilike {

    @Test
    void caseInsensitivePrefix() {
      assertTrue(LikeMatcher.ilike("BMW", "bmw%"));
    }

    @Test
    void caseInsensitiveSuffix() {
      assertTrue(LikeMatcher.ilike("Piet", "%IET"));
    }

    @Test
    void caseInsensitiveContains() {
      assertTrue(LikeMatcher.ilike("the BMW M5", "%bmw%"));
    }

    @Test
    void caseInsensitiveExactMatch() {
      assertTrue(LikeMatcher.ilike("BMW", "bmw"));
    }

    @Test
    void caseInsensitiveSingleCharWildcard() {
      assertTrue(LikeMatcher.ilike("PIET", "pi_t"));
    }

    // ponytail: ASCII fold — German ß does NOT fold to SS under our per-char case-fold.
    // ilike is intended for ASCII identifiers (tenant codes, brand codes, region tags).
    @Test
    void unicodeLimitationGermanSharpS() {
      assertFalse(LikeMatcher.ilike("\u00df", "SS"), "\u00df should not fold to SS under ASCII per-char fold");
    }
  }
}