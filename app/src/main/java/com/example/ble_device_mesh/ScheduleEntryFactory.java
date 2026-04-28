package com.example.ble_device_mesh;

import java.util.ArrayList;
import java.util.List;

import no.nordicsemi.android.mesh.data.ScheduleEntry;

/**
 * Java helper for constructing ScheduleEntry inner class instances.
 *
 * Kotlin 2.0 (K2 compiler) cannot properly access the public static factory methods
 * of ScheduleEntry's inner classes (Hour.Value, Minute.Value, DayOfWeek.Any, etc.)
 * due to the private EntryType base class. Java has no such issue, so this class
 * provides wrapper methods that Kotlin can call.
 */
public class ScheduleEntryFactory {

    public static ScheduleEntry.Year createYear(int year) {
        if (year == 0 || year == 100) {
            return ScheduleEntry.Year.Any;
        } else {
            return ScheduleEntry.Year.Specific(year);
        }
    }

    public static ScheduleEntry.Month createMonthAll() {
        return ScheduleEntry.Month.Any(List.of(
                ScheduleEntry.Month.JANUARY, ScheduleEntry.Month.FEBRUARY, ScheduleEntry.Month.MARCH,
                ScheduleEntry.Month.APRIL, ScheduleEntry.Month.MAY, ScheduleEntry.Month.JUNE,
                ScheduleEntry.Month.JULY, ScheduleEntry.Month.AUGUST, ScheduleEntry.Month.SEPTEMBER,
                ScheduleEntry.Month.OCTOBER, ScheduleEntry.Month.NOVEMBER, ScheduleEntry.Month.DECEMBER
        ));
    }

    public static ScheduleEntry.Day createDayAny() {
        return ScheduleEntry.Day.Any;
    }

    public static ScheduleEntry.Hour createHour(int hour) {
        return ScheduleEntry.Hour.Value(hour);
    }

    public static ScheduleEntry.Minute createMinute(int minute) {
        return ScheduleEntry.Minute.Value(minute);
    }

    public static ScheduleEntry.Second createSecond(int second) {
        return ScheduleEntry.Second.Value(second);
    }

    public static ScheduleEntry.DayOfWeek createDayOfWeekAll() {
        return ScheduleEntry.DayOfWeek.Any(List.of(
                ScheduleEntry.DayOfWeek.SUNDAY,
                ScheduleEntry.DayOfWeek.MONDAY,
                ScheduleEntry.DayOfWeek.TUESDAY,
                ScheduleEntry.DayOfWeek.WEDNESDAY,
                ScheduleEntry.DayOfWeek.THURSDAY,
                ScheduleEntry.DayOfWeek.FRIDAY,
                ScheduleEntry.DayOfWeek.SATURDAY
        ));
    }

    /**
     * Create DayOfWeek from a 7-bit bitmask.
     * bit0=Sun, bit1=Mon, bit2=Tue, bit3=Wed, bit4=Thu, bit5=Fri, bit6=Sat
     * 0x7F = all days
     */
    public static ScheduleEntry.DayOfWeek createDayOfWeek(int repeat) {
        if (repeat == 0x7F) {
            return createDayOfWeekAll();
        }
        ScheduleEntry.DayOfWeek[] dayMapping = {
                ScheduleEntry.DayOfWeek.SUNDAY,     // bit0
                ScheduleEntry.DayOfWeek.MONDAY,     // bit1
                ScheduleEntry.DayOfWeek.TUESDAY,    // bit2
                ScheduleEntry.DayOfWeek.WEDNESDAY,  // bit3
                ScheduleEntry.DayOfWeek.THURSDAY,   // bit4
                ScheduleEntry.DayOfWeek.FRIDAY,     // bit5
                ScheduleEntry.DayOfWeek.SATURDAY    // bit6
        };
        List<ScheduleEntry.DayOfWeek> dayOfWeekList = new ArrayList<>();
        for (int i = 0; i < dayMapping.length; i++) {
            if ((repeat & (1 << i)) != 0) {
                dayOfWeekList.add(dayMapping[i]);
            }
        }
        return ScheduleEntry.DayOfWeek.Any(dayOfWeekList);
    }

    public static ScheduleEntry.Action getActionTurnOn() {
        return ScheduleEntry.Action.TurnOn;
    }

    public static ScheduleEntry.Action getActionTurnOff() {
        return ScheduleEntry.Action.TurnOff;
    }

    public static ScheduleEntry.Action getActionNoAction() {
        return ScheduleEntry.Action.NoAction;
    }

    public static ScheduleEntry.Scene createScene(int sceneValue) {
        if (sceneValue == 0) {
            return ScheduleEntry.Scene.NoScene;
        } else {
            return ScheduleEntry.Scene.Address(sceneValue);
        }
    }
}
