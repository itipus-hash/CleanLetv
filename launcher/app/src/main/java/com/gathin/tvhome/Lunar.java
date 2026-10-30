package com.gathin.tvhome;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * The Chinese lunar date, for the line beside Home's Gregorian one: {@code 丙午年 九月十六}.
 *
 * <p>Pure table lookup, no network and no third-party code. One {@code long} per lunar year packs
 * the whole year: the low nibble is the leap month (0 when there is none), bits 4 to 15 say whether
 * months 1 to 12 are 30 or 29 days long, and bit 16 does the same for the leap month. Days are then
 * counted forward from 1900-01-31, which was 庚子年正月初一.
 *
 * <p>The table covers 1900 to 2099 inclusive, which is past any date a launcher will ever be asked
 * to draw; outside it {@link #of} returns an empty string rather than a guess. It was checked day by
 * day against two independent implementations over all 73,019 days of that range - see
 * {@code dbg/lunar-table-check.py} - so a wrong month length cannot slip through silently.
 */
final class Lunar {
    /** 1900-01-31, 农历庚子年正月初一. */
    private static final long BASE_EPOCH_DAY;
    /** The first and last year the table knows. */
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2099;
    /** Days are counted, not instants, so both ends of the subtraction are read in UTC. */
    private static final java.util.TimeZone UTC = java.util.TimeZone.getTimeZone("UTC");

    static {
        Calendar base = new GregorianCalendar(UTC);
        base.clear();
        base.set(1900, Calendar.JANUARY, 31);
        BASE_EPOCH_DAY = base.getTimeInMillis() / 86400000L;
    }

    /**
     * One entry per lunar year from 1900: bits 15..4 = months 1..12 big(30)/small(29),
     * bit 16 = the leap month's length, bits 3..0 = which month is the leap one (0 = none).
     */
    private static final long[] INFO = {
        0x04BD8, 0x04AE0, 0x0A570, 0x054D5, 0x0D260, 0x0D950, 0x16554, 0x056A0, 0x09AD0, 0x055D2,
        0x04AE0, 0x0A5B6, 0x0A4D0, 0x0D250, 0x1D255, 0x0B540, 0x0D6A0, 0x0ADA2, 0x095B0, 0x14977,
        0x04970, 0x0A4B0, 0x0B4B5, 0x06A50, 0x06D40, 0x1AB54, 0x02B60, 0x09570, 0x052F2, 0x04970,
        0x06566, 0x0D4A0, 0x0EA50, 0x06E95, 0x05AD0, 0x02B60, 0x186E3, 0x092E0, 0x1C8D7, 0x0C950,
        0x0D4A0, 0x1D8A6, 0x0B550, 0x056A0, 0x1A5B4, 0x025D0, 0x092D0, 0x0D2B2, 0x0A950, 0x0B557,
        0x06CA0, 0x0B550, 0x15355, 0x04DA0, 0x0A5D0, 0x14573, 0x052B0, 0x0A9A8, 0x0E950, 0x06AA0,
        0x0AEA6, 0x0AB50, 0x04B60, 0x0AAE4, 0x0A570, 0x05260, 0x0F263, 0x0D950, 0x05B57, 0x056A0,
        0x096D0, 0x04DD5, 0x04AD0, 0x0A4D0, 0x0D4D4, 0x0D250, 0x0D558, 0x0B540, 0x0B5A0, 0x195A6,
        0x095B0, 0x049B0, 0x0A974, 0x0A4B0, 0x0B27A, 0x06A50, 0x06D40, 0x0AF46, 0x0AB60, 0x09570,
        0x04AF5, 0x04970, 0x064B0, 0x074A3, 0x0EA50, 0x06B58, 0x05AC0, 0x0AB60, 0x096D5, 0x092E0,
        0x0C960, 0x0D954, 0x0D4A0, 0x0DA50, 0x07552, 0x056A0, 0x0ABB7, 0x025D0, 0x092D0, 0x0CAB5,
        0x0A950, 0x0B4A0, 0x0BAA4, 0x0AD50, 0x055D9, 0x04BA0, 0x0A5B0, 0x15176, 0x052B0, 0x0A930,
        0x07954, 0x06AA0, 0x0AD50, 0x05B52, 0x04B60, 0x0A6E6, 0x0A4E0, 0x0D260, 0x0EA65, 0x0D530,
        0x05AA0, 0x076A3, 0x096D0, 0x04AFB, 0x04AD0, 0x0A4D0, 0x1D0B6, 0x0D250, 0x0D520, 0x0DD45,
        0x0B5A0, 0x056D0, 0x055B2, 0x049B0, 0x0A577, 0x0A4B0, 0x0AA50, 0x1B255, 0x06D20, 0x0ADA0,
        0x14B63, 0x09370, 0x049F8, 0x04970, 0x064B0, 0x168A6, 0x0EA50, 0x06AA0, 0x1A6C4, 0x0AAE0,
        0x092E0, 0x0D2E3, 0x0C960, 0x0D557, 0x0D4A0, 0x0DA50, 0x05D55, 0x056A0, 0x0A6D0, 0x055D4,
        0x052D0, 0x0A9B8, 0x0A950, 0x0B4A0, 0x0B6A6, 0x0AD50, 0x055A0, 0x0ABA4, 0x0A5B0, 0x052B0,
        0x0B273, 0x06930, 0x07337, 0x06AA0, 0x0AD50, 0x14B55, 0x04B60, 0x0A570, 0x054E4, 0x0D160,
        0x0E968, 0x0D520, 0x0DAA0, 0x16AA6, 0x056D0, 0x04AE0, 0x0A9D4, 0x0A2D0, 0x0D150, 0x0F252,
        0x0D520,
    };

    private static final String[] STEM = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] BRANCH = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] ZODIAC = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};
    /** 正月、二月 … 十月、冬月、腊月 - the names as they are spoken, not as they are numbered. */
    private static final String[] MONTH = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
    private static final String[] DAY_1 = {"初", "十", "廿", "卅"};
    private static final String[] DAY_2 = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** A converted date. */
    static final class Date {
        int year, month, day;
        boolean leap;
    }

    /**
     * The lunar date for a Gregorian one, or null outside the table's range. Cheap enough for the
     * main thread: the year loop walks at most 200 entries.
     */
    static Date of(int year, int month, int day) {
        if (year < MIN_YEAR || year > MAX_YEAR) return null;
        Calendar calendar = new GregorianCalendar(UTC);
        calendar.clear();
        calendar.set(year, month - 1, day);
        long epoch = calendar.getTimeInMillis() / 86400000L;
        long offset = epoch - BASE_EPOCH_DAY;
        if (offset < 0) return null;
        int lunarYear = MIN_YEAR;
        while (lunarYear < MAX_YEAR && offset >= yearDays(lunarYear)) {
            offset -= yearDays(lunarYear);
            lunarYear++;
        }
        int leap = leapMonth(lunarYear);
        Date date = new Date();
        date.year = lunarYear;
        boolean inLeap = false;
        int monthIndex = 0;
        while (true) {
            // Walk the year in the order its months actually happen: 1..12, with the leap month
            // inserted right after the month it repeats.
            if (!inLeap && leap > 0 && monthIndex == leap) {
                inLeap = true;
            } else {
                if (inLeap) inLeap = false;
                monthIndex++;
            }
            long days = inLeap ? leapDays(lunarYear) : monthDays(lunarYear, monthIndex);
            if (offset < days) break;
            offset -= days;
            if (monthIndex >= 12 && !inLeap) break;
        }
        date.month = monthIndex;
        date.day = (int) offset + 1;
        date.leap = inLeap;
        return date;
    }

    /**
     * The whole thing as one line: {@code 丙午年 九月十六}, or {@code ""} when the date is out of
     * the table's range. The stem-and-branch year is what makes it worth showing next to the
     * Gregorian date, which already carries the plain number - and no "农历" prefix, because the
     * 天干地支 already says which calendar this is.
     */
    static String format(Date date) {
        if (date == null) return "";
        StringBuilder line = new StringBuilder();
        int index = date.year - 4;
        line.append(STEM[((index % 10) + 10) % 10]).append(BRANCH[((index % 12) + 12) % 12]);
        line.append('年');
        line.append(' ');
        if (date.leap) line.append('闰');
        line.append(MONTH[date.month - 1]).append("月");
        int day = date.day;
        line.append(DAY_1[(day - 1) / 10]).append(DAY_2[(day - 1) % 10]);
        return line.toString();
    }

    /** The year's animal, for callers that want just that. */
    static String zodiac(Date date) {
        if (date == null) return "";
        int index = date.year - 4;
        return ZODIAC[((index % 12) + 12) % 12];
    }

    private static int leapMonth(int year) {
        return (int) (INFO[year - MIN_YEAR] & 0xF);
    }

    private static long leapDays(int year) {
        if (leapMonth(year) == 0) return 0;
        return (INFO[year - MIN_YEAR] & 0x10000) != 0 ? 30 : 29;
    }

    private static long monthDays(int year, int month) {
        return (INFO[year - MIN_YEAR] & (0x10000 >> month)) != 0 ? 30 : 29;
    }

    private static long yearDays(int year) {
        long sum = 348;
        for (long bit = 0x8000; bit > 0x8; bit >>= 1) {
            if ((INFO[year - MIN_YEAR] & bit) != 0) sum++;
        }
        return sum + leapDays(year);
    }
}
