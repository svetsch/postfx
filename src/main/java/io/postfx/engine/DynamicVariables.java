package io.postfx.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Postman compatible dynamic variables ({{$guid}}, {{$timestamp}}, {{$randomInt}}...). */
public final class DynamicVariables {
    private static final List<String> FIRST = List.of("James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael",
            "Linda", "David", "Elena", "Luca", "Sofia", "Noah", "Emma", "Liam", "Olivia");
    private static final List<String> LAST = List.of("Smith", "Johnson", "Brown", "Miller", "Davis", "Garcia", "Martin",
            "Muller", "Rossi", "Dubois", "Keller", "Schmid", "Meier", "Weber");
    private static final List<String> WORDS = List.of("alpha", "bravo", "cloud", "delta", "echo", "forest", "galaxy",
            "harbor", "island", "jungle", "kernel", "lemon", "matrix", "nebula", "orbit", "pixel", "quartz", "river");
    private static final List<String> CITIES = List.of("Zurich", "Geneva", "Paris", "London", "Berlin", "Madrid",
            "Rome", "Lisbon", "Vienna", "Oslo", "Tokyo", "Toronto");
    private static final List<String> COUNTRIES = List.of("Switzerland", "France", "Germany", "Italy", "Spain",
            "Portugal", "Austria", "Norway", "Japan", "Canada");
    private static final List<String> COLORS = List.of("red", "green", "blue", "orange", "purple", "yellow", "black", "white");
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final Map<String, Supplier<String>> VARS = new TreeMap<>();

    static {
        VARS.put("$guid", () -> UUID.randomUUID().toString());
        VARS.put("$randomUUID", () -> UUID.randomUUID().toString());
        VARS.put("$timestamp", () -> String.valueOf(Instant.now().getEpochSecond()));
        VARS.put("$isoTimestamp", () -> Instant.now().toString());
        VARS.put("$randomInt", () -> String.valueOf(rnd().nextInt(0, 1001)));
        VARS.put("$randomBoolean", () -> String.valueOf(rnd().nextBoolean()));
        VARS.put("$randomAlphaNumeric", () -> String.valueOf(ALNUM.charAt(rnd().nextInt(ALNUM.length()))));
        VARS.put("$randomFirstName", () -> pick(FIRST));
        VARS.put("$randomLastName", () -> pick(LAST));
        VARS.put("$randomFullName", () -> pick(FIRST) + " " + pick(LAST));
        VARS.put("$randomUserName", () -> (pick(FIRST) + "." + pick(LAST)).toLowerCase() + rnd().nextInt(100));
        VARS.put("$randomEmail", () -> (pick(FIRST) + "." + pick(LAST)).toLowerCase() + rnd().nextInt(1000) + "@example.com");
        VARS.put("$randomPassword", () -> randomString(15));
        VARS.put("$randomWord", () -> pick(WORDS));
        VARS.put("$randomWords", () -> pick(WORDS) + " " + pick(WORDS) + " " + pick(WORDS));
        VARS.put("$randomLoremWord", () -> pick(WORDS));
        VARS.put("$randomLoremSentence", () -> capitalize(pick(WORDS)) + " " + pick(WORDS) + " " + pick(WORDS) + " " + pick(WORDS) + ".");
        VARS.put("$randomCity", () -> pick(CITIES));
        VARS.put("$randomCountry", () -> pick(COUNTRIES));
        VARS.put("$randomColor", () -> pick(COLORS));
        VARS.put("$randomHexColor", () -> String.format("#%06x", rnd().nextInt(0x1000000)));
        VARS.put("$randomIP", () -> rnd().nextInt(1, 255) + "." + rnd().nextInt(256) + "." + rnd().nextInt(256) + "." + rnd().nextInt(1, 255));
        VARS.put("$randomPhoneNumber", () -> String.format("%03d-%03d-%04d", rnd().nextInt(200, 999), rnd().nextInt(1000), rnd().nextInt(10000)));
        VARS.put("$randomPrice", () -> String.format(java.util.Locale.ROOT, "%.2f", rnd().nextDouble(1, 1000)));
        VARS.put("$randomDateFuture", () -> LocalDate.now().plusDays(rnd().nextInt(1, 365)).toString());
        VARS.put("$randomDatePast", () -> LocalDate.now().minusDays(rnd().nextInt(1, 365)).toString());
        VARS.put("$randomUrl", () -> "https://" + pick(WORDS) + ".example.com");
    }

    private DynamicVariables() {}

    public static String value(String name) {
        Supplier<String> s = VARS.get(name);
        return s == null ? null : s.get();
    }

    public static java.util.Set<String> names() {
        return VARS.keySet();
    }

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    private static String pick(List<String> l) {
        return l.get(rnd().nextInt(l.size()));
    }

    private static String randomString(int len) {
        StringBuilder sb = new StringBuilder();
        String chars = ALNUM + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd().nextInt(chars.length())));
        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
