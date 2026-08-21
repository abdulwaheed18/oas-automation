package com.oastest.automation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort generator of a string that <b>matches</b> a regular expression, used so the valid
 * baseline request satisfies an OpenAPI {@code pattern} constraint (e.g. a header defined as
 * {@code [A-Z0-9]{3}} must be sampled as {@code "AAA"}, not {@code "sample"}).
 *
 * <p>It covers the regex subset that appears in real OpenAPI specs — literals, character classes
 * and ranges, shorthands ({@code \d \w \s}), groups, alternation and quantifiers ({@code {n} {n,m}
 * ? * +}). Every candidate it builds is validated against the real {@link Pattern}; if it cannot
 * produce a match it returns {@code null} and the caller falls back to a plain sample.</p>
 */
public final class RegexSampler {

    private RegexSampler() {
    }

    /** Returns a string that fully matches {@code regex}, or {@code null} if none could be built. */
    public static String sample(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        try {
            String candidate = new Parser(regex).parse();
            if (candidate != null && matchesFully(regex, candidate)) {
                return candidate;
            }
        } catch (RuntimeException ignored) {
            // fall through to null
        }
        return null;
    }

    public static boolean matchesFully(String regex, String value) {
        try {
            return Pattern.compile(regex).matcher(value).matches();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Minimal recursive-descent regex → sample builder. */
    private static final class Parser {
        private final String re;
        private int i;

        Parser(String re) {
            this.re = re;
        }

        String parse() {
            String s = alternation();
            return s;
        }

        // alternation := sequence ('|' sequence)*   — we pick the first branch
        private String alternation() {
            String first = sequence();
            while (peek() == '|') {
                i++;            // consume '|'
                sequence();     // parse & discard the other branches
            }
            return first;
        }

        private String sequence() {
            StringBuilder sb = new StringBuilder();
            while (i < re.length() && peek() != '|' && peek() != ')') {
                sb.append(quantified());
            }
            return sb.toString();
        }

        private String quantified() {
            String atom = atom();
            char c = peek();
            if (c == '{') {
                int[] bounds = braces();
                return atom.repeat(Math.max(0, bounds[0]));
            } else if (c == '?') {
                i++;
                return atom; // 1 occurrence still matches "0 or 1"
            } else if (c == '*') {
                i++;
                consumeLazy();
                return atom; // 1 occurrence matches "0 or more"
            } else if (c == '+') {
                i++;
                consumeLazy();
                return atom;
            }
            return atom;
        }

        private void consumeLazy() {
            if (peek() == '?') {
                i++;
            }
        }

        private String atom() {
            char c = peek();
            if (c == '(') {
                i++; // '('
                if (peek() == '?') {         // non-capturing / flags group: (?: ...)
                    i++;
                    while (peek() != ':' && peek() != ')' && i < re.length()) {
                        i++;
                    }
                    if (peek() == ':') {
                        i++;
                    }
                }
                String inner = alternation();
                if (peek() == ')') {
                    i++;
                }
                return inner;
            }
            if (c == '[') {
                return charClass();
            }
            if (c == '\\') {
                i++;
                return escaped(next());
            }
            if (c == '.') {
                i++;
                return "a";
            }
            if (c == '^' || c == '$') {
                i++;
                return "";
            }
            i++;
            return String.valueOf(c);
        }

        private String escaped(char c) {
            return switch (c) {
                case 'd' -> "5";
                case 'D' -> "a";
                case 'w' -> "a";
                case 'W' -> "-";
                case 's' -> " ";
                case 'S' -> "a";
                case 'n' -> "\n";
                case 't' -> "\t";
                default -> String.valueOf(c);
            };
        }

        // Parses [...] and returns one representative character that satisfies it.
        private String charClass() {
            i++; // '['
            boolean negated = false;
            if (peek() == '^') {
                negated = true;
                i++;
            }
            List<char[]> ranges = new ArrayList<>();
            List<Character> singles = new ArrayList<>();
            while (i < re.length() && peek() != ']') {
                char c = next();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case 'd' -> ranges.add(new char[]{'0', '9'});
                        case 'w' -> {
                            ranges.add(new char[]{'a', 'z'});
                            ranges.add(new char[]{'A', 'Z'});
                            ranges.add(new char[]{'0', '9'});
                            singles.add('_');
                        }
                        case 's' -> singles.add(' ');
                        default -> singles.add(e);
                    }
                    continue;
                }
                if (peek() == '-' && i + 1 < re.length() && re.charAt(i + 1) != ']') {
                    i++; // '-'
                    char hi = next();
                    ranges.add(new char[]{c, hi});
                } else {
                    singles.add(c);
                }
            }
            if (peek() == ']') {
                i++;
            }
            if (!negated) {
                if (!ranges.isEmpty()) {
                    return String.valueOf(ranges.get(0)[0]);
                }
                if (!singles.isEmpty()) {
                    return String.valueOf(singles.get(0));
                }
                return "a";
            }
            // negated: pick a printable char not covered
            for (char cand : new char[]{'A', 'a', '0', 'x', '1', 'Z', '9', '-'}) {
                if (!inClass(cand, ranges, singles)) {
                    return String.valueOf(cand);
                }
            }
            return "a";
        }

        private boolean inClass(char c, List<char[]> ranges, List<Character> singles) {
            for (char[] r : ranges) {
                if (c >= r[0] && c <= r[1]) {
                    return true;
                }
            }
            return singles.contains(c);
        }

        // Parses {n} / {n,} / {n,m} and returns [min, max].
        private int[] braces() {
            i++; // '{'
            StringBuilder lo = new StringBuilder();
            StringBuilder hi = new StringBuilder();
            boolean comma = false;
            while (i < re.length() && peek() != '}') {
                char c = next();
                if (c == ',') {
                    comma = true;
                } else if (Character.isDigit(c)) {
                    (comma ? hi : lo).append(c);
                }
            }
            if (peek() == '}') {
                i++;
            }
            consumeLazy();
            int min = lo.length() > 0 ? Integer.parseInt(lo.toString()) : 0;
            int max = hi.length() > 0 ? Integer.parseInt(hi.toString()) : (comma ? min + 2 : min);
            return new int[]{min, max};
        }

        private char peek() {
            return i < re.length() ? re.charAt(i) : '\0';
        }

        private char next() {
            return re.charAt(i++);
        }
    }
}
