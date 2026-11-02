package fr.emploigo.establishment.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the names and addresses of the State registers are shown: the registers write them in
 * capitals, and a sign is sometimes a code.
 */
public final class DisplayName {
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{4,}");
    private static final Pattern VOWEL = Pattern.compile("[aeiouyàâäéèêëîïôöùûüÿœæ]");
    private static final Set<String> SMALL_WORDS =
            Set.of(
                    "a", "à", "au", "aux", "chez", "d", "de", "des", "du", "en", "et", "l", "la",
                    "le", "les", "lès", "par", "pour", "sous", "sur", "un", "une");
    // The acronyms with a vowel that the names of the registers carry most: legal forms, care,
    // social and public bodies. One without a vowel is kept anyway.
    private static final Set<String> ACRONYMS =
            Set.of(
                    "ADAPEI", "ADMR", "AFPA", "APAJH", "CAF", "CCAS", "CFA", "CHU", "CIAS", "CNRS",
                    "CPAM", "CROUS", "EARL", "EDF", "EHPAD", "ESAT", "ESN", "EURL", "GAEC", "GIE",
                    "IME", "INRAE", "INSERM", "ITEP", "IUT", "MJC", "MSA", "OGEC", "ONF", "PEP",
                    "SAMU", "SARL", "SAS", "SASU", "SCI", "SCOP", "SDIS", "SELARL", "SELAS",
                    "SESSAD", "UDAF", "UFR", "URSSAF", "VYV");
    private static final Locale FRENCH = Locale.FRENCH;

    private DisplayName() {}

    /**
     * The name of an establishment: its sign, or the name of its company when the sign is a code
     * such as « REF 161529 ».
     */
    public static String ofEstablishment(String name, String companyName) {
        return of(isCode(name) ? companyName : name);
    }

    /**
     * A text in capitals in the case of French, « BOULANGERIE DE LA GARE » becoming « Boulangerie
     * de la Gare »; a text that has lower-case letters is kept as written. A word without a vowel,
     * or a known acronym, stays in capitals (« SNCF », « CHU »).
     */
    public static String of(String text) {
        if (text.codePoints().anyMatch(Character::isLowerCase)) return text;
        Matcher words = WORD.matcher(text);
        StringBuilder shown = new StringBuilder();
        int end = 0;
        while (words.find()) {
            String before = text.substring(end, words.start());
            shown.append(before);
            String word = words.group().toLowerCase(FRENCH);
            boolean first = end == 0 && before.isBlank();
            boolean elided = before.endsWith("'") || before.endsWith("’");
            if (ACRONYMS.contains(words.group())
                    || !VOWEL.matcher(word).find() && word.length() > 1)
                shown.append(words.group());
            else if (!first && !elided && SMALL_WORDS.contains(word)) shown.append(word);
            else shown.append(capitalized(word));
            end = words.end();
        }
        return shown.append(text.substring(end)).toString();
    }

    private static boolean isCode(String name) {
        long letters = name.codePoints().filter(Character::isLetter).count();
        return LONG_NUMBER.matcher(name).find() && letters < 4;
    }

    private static String capitalized(String word) {
        return word.substring(0, 1).toUpperCase(FRENCH) + word.substring(1);
    }
}
