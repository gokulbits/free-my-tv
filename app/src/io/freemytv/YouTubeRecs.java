package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube's own picks for your account, as Google TV's home screen showed them: what the YouTube app and SmartTube
 * put in Android TV's lists (TvContract, READ_TV_LISTINGS, the same permission as Continue Watching). Their Watch
 * Next entries are what you're in the middle of; their home screen channels (YouTube's recommendations, SmartTube's
 * Subscriptions, Recommended, History…) are the rest. Only videos (an 11-character id): they open in SmartTube when
 * the TV has it (straight to the video), else in the YouTube app. Off with Home Settings > Picks from > YouTube.
 */
final class YouTubeRecs {
    private YouTubeRecs() {}

    static final String KEY = "youtube";
    /** SmartTube's builds (stable, beta, F-Droid, and the older names), first found wins. */
    static final String[] SMARTTUBE = {"org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid",
            "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta"};
    /** The YouTube apps that publish (Android TV / Google TV, Fire TV). */
    static final String[] YOUTUBE = {"com.google.android.youtube.tv", "com.amazon.firetv.youtube"};
    static final int PROGRESS_MAX = 4, RECS_MAX = 8;
    /** A YouTube video id in a link or a field: watch?v=, youtu.be/, vnd.youtube:, /shorts/, or the id alone. */
    private static final Pattern VIDEO = Pattern.compile("(?:[?&]v=|youtu\\.be/|vnd\\.youtube:/*|/shorts/|/embed/|^)([A-Za-z0-9_-]{11})(?:$|[^A-Za-z0-9_-])");

    static final class Lists {
        final List<Recs.Pick> progress = new ArrayList<>(), recommended = new ArrayList<>();
    }

    /** The app the videos open in: SmartTube, else YouTube; null for neither (no YouTube picks). */
    static String client(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : SMARTTUBE) if (HomeApps.launchIntent(pm, p) != null) return p;
        for (String p : YOUTUBE) if (HomeApps.launchIntent(pm, p) != null) return p;
        return null;
    }

    /** Wanted on this TV: an app to open them in and YouTube not switched off in Picks from. */
    static boolean wanted(Context c) {
        return !Prefs.serviceOff(KEY) && client(c) != null;
    }

    /** The video's page: both SmartTube and YouTube open it straight to the video. */
    static String link(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    /** The lists, from the TV's own provider (no network). Off the main thread. */
    static Lists load(Context c) {
        Lists out = new Lists();
        String client = client(c);
        if (client == null || !WatchNext.allowed(c)) return out;
        Set<String> publishers = new HashSet<>(Arrays.asList(SMARTTUBE));
        publishers.addAll(Arrays.asList(YOUTUBE));
        ContentResolver cr = c.getContentResolver();
        Set<String> seen = new HashSet<>();
        // 1. Watch Next: in progress (newest first); saved-for-later goes with the recommendations.
        List<Row> next = rows(cr, TvContract.WatchNextPrograms.CONTENT_URI, publishers, true);
        Collections.sort(next, (a, b) -> Long.compare(b.engaged, a.engaged));
        for (Row r : next) {
            if (r.watchlist) continue;
            add(out.progress, seen, r, client, Recs.PROGRESS, PROGRESS_MAX);
        }
        // 2. The apps' home screen channels, one video of each in turn, so one channel doesn't fill it.
        Map<Long, List<Row>> channels = new LinkedHashMap<>();
        for (long id : channels(cr, publishers)) channels.put(id, rows(cr, TvContract.buildPreviewProgramsUriForChannel(id), publishers, false));
        for (Row r : next) if (r.watchlist) add(out.recommended, seen, r, client, Recs.LIKED, RECS_MAX);
        for (int round = 0; out.recommended.size() < RECS_MAX && round < 20; round++) {
            boolean any = false;
            for (List<Row> list : channels.values()) {
                if (round >= list.size()) continue;
                any = true;
                add(out.recommended, seen, list.get(round), client, Recs.LIKED, RECS_MAX);
            }
            if (!any) break;
        }
        return out;
    }

    private static final class Row {
        String title, author, intent, art, providerId, contentId;
        long duration, position, engaged;
        boolean watchlist;
    }

    /** Home screen channels (not the tuner's) of the YouTube apps. */
    private static List<Long> channels(ContentResolver cr, Set<String> publishers) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI, new String[] {TvContract.Channels._ID,
                TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE}, null, null, null)) {
            if (cur == null) return ids;
            while (cur.moveToNext()) {
                String pkg = cur.isNull(1) ? null : cur.getString(1);
                if (pkg != null && publishers.contains(pkg) && TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(2))) {
                    ids.add(cur.getLong(0));
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("youtube channels: " + e.getMessage());
        }
        return ids;
    }

    private static List<Row> rows(ContentResolver cr, Uri uri, Set<String> publishers, boolean watchNext) {
        List<Row> out = new ArrayList<>();
        List<String> projection = new ArrayList<>(Arrays.asList(
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_AUTHOR,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.WatchNextPrograms.COLUMN_CONTENT_ID,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS));
        if (watchNext) {
            projection.add(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            projection.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        try (Cursor cur = cr.query(uri, projection.toArray(new String[0]), null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String pkg = text(cur, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (pkg == null || !publishers.contains(pkg)) continue;
                if ("0".equals(text(cur, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                Row r = new Row();
                r.title = text(cur, TvContract.WatchNextPrograms.COLUMN_TITLE);
                if (r.title == null || r.title.trim().isEmpty()) continue;
                r.author = text(cur, TvContract.WatchNextPrograms.COLUMN_AUTHOR);
                r.intent = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                String thumb = text(cur, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                r.art = thumb != null ? thumb : text(cur, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                r.providerId = text(cur, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID);
                r.contentId = text(cur, TvContract.WatchNextPrograms.COLUMN_CONTENT_ID);
                r.duration = number(cur, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                r.position = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                r.engaged = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                r.watchlist = watchNext && number(cur, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
                        == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST;
                out.add(r);
            }
        } catch (RuntimeException e) {   // a provider that refuses means nothing to show
            AppLog.w("youtube " + uri + ": " + e.getMessage());
        }
        return out;
    }

    /** The row as a pick (a video only), into `out` up to `max`, once per video. */
    private static void add(List<Recs.Pick> out, Set<String> seen, Row r, String client, int mine, int max) {
        if (out.size() >= max) return;
        String id = videoId(r);
        if (id == null || !seen.add(id)) return;
        Recs.Pick p = new Recs.Pick();
        p.youtube = true;
        p.id = -(1 + (id.hashCode() & 0x3fffffff));   // negative: never a TMDB number
        p.kind = "video";                             // Explore only: not a movie or a show
        p.title = r.title.trim();
        // YouTube's own 1280×720 still; when a video has none, Recs falls back to the 480×360 one (its bars crop off).
        p.backdrop = "https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg";
        p.app = KEY;
        p.pkg = client;
        p.link = link(id);
        p.mine = mine;
        List<String> parts = new ArrayList<>();
        if (mine == Recs.PROGRESS) {
            p.tag = Ui.tr("Continue watching");
            parts.add(p.tag);
        }
        if (r.author != null && !r.author.trim().isEmpty()) parts.add(r.author.trim());
        String length = r.duration > 0 ? Recs.runtime((int) Math.max(1, r.duration / 60_000L)) : null;
        if (length != null) parts.add(length);
        parts.add("YouTube");
        p.meta = String.join(" · ", parts);
        p.detailed = true;
        out.add(p);
    }

    /** The video's id: SmartTube keeps it as the provider id; YouTube's links carry it. Null: not a single video. */
    private static String videoId(Row r) {
        for (String s : new String[] {r.providerId, r.contentId, r.intent}) {
            if (s == null) continue;
            Matcher m = VIDEO.matcher(s);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
