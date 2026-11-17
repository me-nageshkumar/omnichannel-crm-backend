package me.nageshk.omnichannel.service.billing.money;

import java.math.BigDecimal;

/** An amount as it is written on an Indian invoice: "Rupees One Lakh Twenty Thousand and Fifty Paise Only". */
public final class AmountInWords {

    private static final String[] ONES = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty",
            "Ninety"};

    private AmountInWords() {}

    public static String rupees(BigDecimal amount) {
        BigDecimal rounded = Money.round(amount.abs());
        long whole = rounded.longValue();
        int paise = rounded.subtract(BigDecimal.valueOf(whole)).movePointRight(2).intValue();
        String words = "Rupees " + number(whole);
        if (paise > 0) words += " and " + number(paise) + " Paise";
        return words + " Only";
    }

    /** Indian grouping: crore, lakh, thousand, hundred. */
    private static String number(long n) {
        if (n < 20) return ONES[(int) n];
        if (n < 100) return TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : "-" + ONES[(int) (n % 10)]);
        if (n < 1_000) return join(ONES[(int) (n / 100)] + " Hundred", n % 100);
        if (n < 100_000) return join(number(n / 1_000) + " Thousand", n % 1_000);
        if (n < 10_000_000) return join(number(n / 100_000) + " Lakh", n % 100_000);
        return join(number(n / 10_000_000) + " Crore", n % 10_000_000);
    }

    private static String join(String head, long rest) {
        return rest == 0 ? head : head + " " + number(rest);
    }
}
