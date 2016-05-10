package se.kth.news.core;

import se.sics.kompics.timer.SchedulePeriodicTimeout;
import se.sics.kompics.timer.Timeout;

public class LogTimeout extends Timeout {
	public LogTimeout(SchedulePeriodicTimeout spt) {
		super(spt);
	}
}