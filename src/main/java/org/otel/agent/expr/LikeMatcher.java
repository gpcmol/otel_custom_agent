package org.otel.agent.expr;

/**
 * SQL-style {@code LIKE} wildcard matcher — zero allocation, JIT-inlineable.
 *
 * <p>Translates {@code %} (any sequence, including empty) and {@code _} (exactly one character)
 * into a sequence of {@code startsWith}, {@code endsWith}, {@code contains}, and per-character
 * equality checks. No {@code java.util.regex} is used.
 *
 * <p>The matcher scans the pattern left-to-right, splitting on {@code %} wildcards. Each segment
 * between wildcards is a literal that must appear in order:
 * <ul>
 *   <li>{@code "BMW%"} → {@code startsWith("BMW")}
 *   <li>{@code "%iet"} → {@code endsWith("iet")}
 *   <li>{@code "%Pie%"} → {@code contains("Pie")}
 *   <li>{@code "Pi_t"} → length 4 + char-by-char with {@code _} matching any one char
 * </ul>
 */
public final class LikeMatcher {

  private LikeMatcher() {}

  /**
   * Case-sensitive SQL {@code LIKE} match.
   *
   * @param value the string to test
   * @param pattern the SQL wildcard pattern ({@code %} = any sequence, {@code _} = one char)
   * @return {@code true} if {@code value} matches {@code pattern}
   */
  public static boolean like(final String value, final String pattern) {
    return match(value, pattern, false);
  }

  /**
   * Case-insensitive SQL {@code ILIKE} match.
   *
   * <p>Uses ASCII per-character case-fold ({@link Character#toLowerCase} then {@link
   * Character#toUpperCase}). German ß and Turkish I are NOT correctly folded — this is a
   * documented V1 limitation for ASCII identifiers (tenant codes, brand codes).
   *
   * @param value the string to test
   * @param pattern the SQL wildcard pattern ({@code %} = any sequence, {@code _} = one char)
   * @return {@code true} if {@code value} matches {@code pattern} case-insensitively
   */
  public static boolean ilike(final String value, final String pattern) {
    return match(value, pattern, true);
  }

  private static boolean match(final String value, final String pattern, final boolean ignoreCase) {
    if (value == null || pattern == null) return false;
    if (pattern.isEmpty()) return value.isEmpty();
    // ponytail: recursive backtracking matcher. For V1 patterns (typically <20 chars with 1-3
    // wildcards) the worst case is linear-ish. If pathological patterns (hundreds of %%%)
    // become a concern, switch to a DP table — but V1 configs won't hit that.
    return matchSegment(value, 0, pattern, 0, ignoreCase);
  }

  private static boolean matchSegment(
      final String value, final int vi, final String pattern, final int pi, final boolean ignoreCase) {
    int vpos = vi;
    int ppos = pi;
    while (ppos < pattern.length()) {
      final char pc = pattern.charAt(ppos);
      if (pc == '%') {
        // Skip consecutive %
        while (ppos < pattern.length() && pattern.charAt(ppos) == '%') ppos++;
        if (ppos == pattern.length()) return true; // trailing % matches everything
        // Try matching the rest at every remaining position
        for (int skip = vpos; skip <= value.length(); skip++) {
          if (matchSegment(value, skip, pattern, ppos, ignoreCase)) return true;
        }
        return false;
      } else if (pc == '_') {
        if (vpos >= value.length()) return false;
        vpos++;
        ppos++;
      } else {
        if (vpos >= value.length()) return false;
        final char vc = value.charAt(vpos);
        if (!charEquals(vc, pc, ignoreCase)) return false;
        vpos++;
        ppos++;
      }
    }
    return vpos == value.length();
  }

  private static boolean charEquals(final char a, final char b, final boolean ignoreCase) {
    if (a == b) return true;
    if (!ignoreCase) return false;
    // ponytail: ASCII fold — toLowerCase then toUpperCase. Misses ß→SS and locale-specific I.
    return Character.toUpperCase(Character.toLowerCase(a))
        == Character.toUpperCase(Character.toLowerCase(b));
  }
}