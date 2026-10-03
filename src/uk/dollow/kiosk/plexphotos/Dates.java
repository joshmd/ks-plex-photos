// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.util.Calendar;
import java.util.GregorianCalendar;

/** The "Taken within" filter. Avoids java.time, which needs API 26 without desugaring. */
final class Dates {
    private Dates() { }

    static boolean matches(String iso, String within, Calendar today) {
        if ("Any time".equals(within)) return true;
        if (iso.length() < 10) return false;
        int y, m, d;
        try {
            y = Integer.parseInt(iso.substring(0, 4));
            m = Integer.parseInt(iso.substring(5, 7));
            d = Integer.parseInt(iso.substring(8, 10));
        } catch (NumberFormatException e) {
            return false;
        }
        if (m < 1 || m > 12 || d < 1 || d > 31) return false;

        if ("On this day".equals(within)) {
            int photoDay = dayOfYear(m, d);
            int todayDay = dayOfYear(today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH));
            int diff = Math.abs(photoDay - todayDay);
            return Math.min(diff, 365 - diff) <= 3 && y < today.get(Calendar.YEAR);
        }

        Calendar cutoff = (Calendar) today.clone();
        switch (within) {
            case "Past month": cutoff.add(Calendar.MONTH, -1); break;
            case "Past year": cutoff.add(Calendar.YEAR, -1); break;
            case "Past 2 years": cutoff.add(Calendar.YEAR, -2); break;
            case "Past 5 years": cutoff.add(Calendar.YEAR, -5); break;
            case "Past 10 years": cutoff.add(Calendar.YEAR, -10); break;
            default: return true;
        }
        Calendar taken = new GregorianCalendar(y, m - 1, d);
        return !taken.before(cutoff);
    }

    /** Day of year in a fixed non-leap year, so 29 February sits beside 28 February. */
    private static int dayOfYear(int month, int day) {
        int[] start = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        return start[month - 1] + Math.min(day, 28 + (month == 2 ? 0 : 3));
    }
}
