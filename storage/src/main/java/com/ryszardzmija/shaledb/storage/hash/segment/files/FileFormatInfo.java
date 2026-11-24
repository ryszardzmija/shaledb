package com.ryszardzmija.shaledb.storage.hash.segment.files;

import java.util.Locale;

class FileFormatInfo {
    private static final long FIRST_ID = 1;
    private static final String PREFIX = "segment_";
    private static final String SUFFIX = ".log";
    private static final int ID_WIDTH = 19;
    private static final int LEGACY_ID_WIDTH = 6;

    static String filename(long id) {
        if (id < FIRST_ID) {
            throw new IllegalArgumentException("Segment ID must be positive: " + id);
        }
        return String.format(Locale.ROOT, PREFIX + "%0" + ID_WIDTH + "d" + SUFFIX, id);
    }

    static boolean isSegmentName(String filename) {
        return filename.startsWith(PREFIX);
    }

    static long parseId(String filename) {
        if (!isSegmentName(filename) || !filename.endsWith(SUFFIX)
                || filename.length() <= PREFIX.length() + SUFFIX.length()) {
            throw new NumberFormatException("Expected a segment name with a decimal ID and a " + SUFFIX + " suffix");
        }

        String digits = filename.substring(PREFIX.length(), filename.length() - SUFFIX.length());
        char first = digits.charAt(0);
        int firstDigit = Character.digit(first, 10);
        int zero = first - firstDigit;
        if (firstDigit < 0 || !digits.chars().allMatch(ch -> ch >= zero && ch <= zero + 9)) {
            throw new NumberFormatException("Segment ID must use a single set of decimal digits");
        }

        long id = Long.parseLong(digits);
        if (id < FIRST_ID) {
            throw new NumberFormatException("Segment ID must be positive");
        }

        // Older readers accepted any ASCII padding. Localized names must match a historical writer's width.
        if (zero != '0' && digits.length() != ID_WIDTH
                && (id > Integer.MAX_VALUE || digits.length() != Math.max(LEGACY_ID_WIDTH, Long.toString(id).length()))) {
            throw new NumberFormatException("Segment ID uses unsupported padding for localized digits");
        }
        return id;
    }
}
