//package org.vaadin.cored;
//
//import java.util.Date;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.Map.Entry;
//
//import org.vaadin.aceeditor.collab.DocDiff;
//import org.vaadin.aceeditor.collab.gwt.shared.Doc;
//import org.vaadin.aceeditor.gwt.shared.LockMarkerData;
//import org.vaadin.aceeditor.gwt.shared.Marker;
//import org.vaadin.diffsync.DiffTask;
//
///**
// * Cleans up old markers.
// * 
// */
//public class MarkerCleanupTask implements DiffTask<Doc, DocDiff> {
//
//	private HashMap<String, Date> markerDates = new HashMap<String, Date>();
//
//	private Date nextCheckTime;
//
//	private static final long LOCK_LIFETIME_MS = 5L * 60L * 1000L;
//
//	private static final long MIN_CHECK_INTERVAL_MS = 60L * 1000L;
//
//
//	private static boolean isUserLock(Marker marker) {
//		if (marker.getType() != Marker.Type.LOCK) {
//			return false;
//		}
//		LockMarkerData data = (LockMarkerData) marker.getData();
//		return data != null;
//	}
//
//	public DocDiff exec(Doc value, DocDiff diff, long collaboratorId) {
//		Date now = new Date();
//		if (nextCheckTime != null && nextCheckTime.after(now)) {
//			return null;
//		}
//		
//		final Date lockCutoff = new Date(now.getTime() - LOCK_LIFETIME_MS);
//		Date cutoff = null;
//		HashSet<String> removeMarkers = new HashSet<String>();
//		for (Entry<String, Marker> e : value.getMarkers().entrySet()) {
//			Marker m = e.getValue();
//			if (isUserLock(m)) {
//				cutoff = lockCutoff;
//			} else {
//				continue;
//			}
//			Date d = markerDates.get(e.getKey());
//			if (d == null) {
//				markerDates.put(e.getKey(), new Date());
//			} else if (d.before(cutoff)) {
//				markerDates.remove(e.getKey());
//			}
//		}
//
//		// TODO: clean up markerDates
//
//		nextCheckTime = new Date(now.getTime() + MIN_CHECK_INTERVAL_MS);
//
//		if (removeMarkers.isEmpty()) {
//			return null;
//		} else {
//			return DocDiff.removeMarkers(removeMarkers);
//		}
//	}
//
//}
