package com.abheenash.triage.report;

/**
 * The handful of terminal escapes the text reporter uses.
 *
 * <p>The escape character is built from its code point rather than written literally, so the
 * source file contains no control bytes. A literal ESC in source survives badly: it is invisible
 * in review, editors and patch tools mangle it, and a corrupted one produces output that is
 * broken in a way nobody can see by reading the diff.
 */
final class Ansi {

    private static final String ESC = String.valueOf((char) 27) + "[";

    static final String RESET = ESC + "0m";
    static final String BOLD = ESC + "1m";
    static final String BOLD_RED = ESC + "1;31m";
    static final String RED = ESC + "31m";
    static final String YELLOW = ESC + "33m";
    static final String CYAN = ESC + "36m";
    static final String GREY = ESC + "90m";

    private Ansi() {
    }
}
