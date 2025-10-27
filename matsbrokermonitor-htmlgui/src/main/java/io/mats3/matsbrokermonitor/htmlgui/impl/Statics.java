package io.mats3.matsbrokermonitor.htmlgui.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * @author Endre Stølsvik 2022-03-13 23:36 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface Statics {

    DateTimeFormatter DATE_TIME_FORMATTER_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    DateTimeFormatter DATE_TIME_FORMATTER_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    int FORCE_UPDATE_TIMEOUT = 5000;

    String MATS3_HTML = "Mats<sup style='font-size: 75%;'>3</sup>";

    Random RANDOM = new Random();

    default String random() {
        String ALPHABET =  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int length = 8;
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String formatTimestampSpan(long timestamp) {
        long millisAgo = System.currentTimeMillis() - timestamp;
        return formatTimestamp(timestamp)
                + " (" + millisSpanToHuman(millisAgo) + ")";
    }

    static String formatTimestamp(long timestamp) {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return (System.currentTimeMillis() - timestamp < 60_000)
                ? now.format(DATE_TIME_FORMATTER_MS)
                : now.format(DATE_TIME_FORMATTER_SEC);
    }

    static String millisSpanToHuman(long millis) {
        if (millis < 1_000) {
            return millis + "ms";
        }
        else if (millis < 10_000) {
            return String.format("%d.%03ds", millis / 1000L, millis % 1000L);
        }
        else {
            Duration d = Duration.ofMillis(millis);
            long days = d.toDays();
            d = d.minusDays(days);
            long hours = d.toHours();
            d = d.minusHours(hours);
            long minutes = d.toMinutes();
            d = d.minusMinutes(minutes);
            long seconds = d.getSeconds();

            StringBuilder buf = new StringBuilder();
            if (days > 0) {
                buf.append(days).append("d");
            }
            if ((hours > 0) || (!buf.isEmpty())) {
                if (!buf.isEmpty()) {
                    buf.append(":");
                }
                buf.append(hours).append("h");
            }
            // ?: Are we <1 day?
            if (millis < 24 * 60 * 60 * 1000) {
                // -> Yes, then add minutes.
                if ((minutes > 0) || (!buf.isEmpty())) {
                    if (!buf.isEmpty()) {
                        buf.append(":");
                    }
                    buf.append(minutes).append("m");
                }
            }
            // ?: Are we <1 hour?
            if (millis < 60 * 60 * 1000) {
                // -> Yes, then add seconds
                if (!buf.isEmpty()) {
                    buf.append(":");
                }
                buf.append(seconds).append("s");
            }
            return buf.toString();
        }
    }
}
