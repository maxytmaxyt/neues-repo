package de.novium.dev.util;

public final class RomanNumerals {

    private RomanNumerals() {}

    private static final int[]    VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    /**
     * Converts a positive integer to its Roman numeral representation.
     *
     * @param number a positive integer (1–3999)
     * @return the Roman numeral string, e.g. {@code toRoman(4)} → {@code "IV"}
     * @throws IllegalArgumentException if {@code number} is not in range 1–3999
     */
    public static String toRoman(int number) {
        if (number < 1 || number > 3999) {
            throw new IllegalArgumentException("Zahl außerhalb des gültigen Bereichs (1–3999): " + number);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            while (number >= VALUES[i]) {
                sb.append(SYMBOLS[i]);
                number -= VALUES[i];
            }
        }
        return sb.toString();
    }
}
