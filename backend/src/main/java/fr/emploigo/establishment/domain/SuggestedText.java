package fr.emploigo.establishment.domain;

/** A text typed in « Quoi ? » or « Où ? »: with too few letters, it matches too much to suggest. */
public final class SuggestedText {
    static final int MIN_LETTERS = 2;

    private SuggestedText() {}

    public static boolean isLongEnough(String text) {
        return text.codePoints().filter(Character::isLetter).count() >= MIN_LETTERS;
    }
}
