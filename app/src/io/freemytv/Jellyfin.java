package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Your Jellyfin server's picks (config.properties "jellyfin_url" and "jellyfin_user"; the key is sent to the TV with
 * tools/send_key.py, never built in): what you're in the middle of and what comes next, then Jellyfin's own
 * recommendations (movies like the ones you watched, shows like the ones you're watching), the newest unwatched ones
 * when it has none. They open in Wholphin when the TV has it, else in Jellyfin's own app. Called on Recs' thread only.
 */
final class Jellyfin {
    static final String APP = "org.jellyfin.androidtv";
    static final String WHOLPHIN = "com.github.damontecres.wholphin";
    /** At most this many per kind: each comes with its images, downloaded when the list is made. */
    static final int PROGRESS_MAX = Recs.MAX_CARDS, RECS_MAX = 4;
    static final int MAX = PROGRESS_MAX + 2 * RECS_MAX;
    private static final String FIELDS = "&Fields=Genres,ChildCount&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1"
            + "&EnableTotalRecordCount=false";

    private Jellyfin() {}

    static final class Lists {
        final List<Recs.Pick> progress = new ArrayList<>(), movies = new ArrayList<>(), shows = new ArrayList<>();
    }

    static boolean configured() {
        return !Config.JELLYFIN_URL.trim().isEmpty();
    }

    /** The app the picks open in: Wholphin when the TV has it, else Jellyfin's own; null for neither. */
    static String client(Context c) {
        PackageManager pm = c.getPackageManager();
        if (HomeApps.launchIntent(pm, WHOLPHIN) != null) return WHOLPHIN;
        if (HomeApps.launchIntent(pm, APP) != null) return APP;
        return null;
    }

    /** What changes the list: the server, the user, the key arriving (when, never what) and which app opens them. */
    static long stamp(Context c) {
        String client = client(c);
        return ((Config.JELLYFIN_URL.trim() + "|" + Config.JELLYFIN_USER.trim()).hashCode() * 31L
                + Secrets.received(c, Secrets.JELLYFIN_KEY)) * 31L + (client == null ? 0 : client.hashCode());
    }

    /**
     * The item's page in that app: Wholphin answers wholphin://view?itemId=…, Jellyfin's StartupActivity a VIEW whose
     * data is the item's id (HomeApps.open names that activity).
     */
    static String link(String pkg, String itemId) {
        return WHOLPHIN.equals(pkg) ? "wholphin://view?itemId=" + itemId : itemId;
    }

    // MARK: - The lists

    static Lists lists(Context c, String client) throws Exception {
        String key = Secrets.get(c, Secrets.JELLYFIN_KEY);
        if (key.isEmpty()) throw new IOException("no jellyfin_api_key on this TV yet: send it with tools/send_key.py");
        String user = URLEncoder.encode(userId(key), "UTF-8");
        Lists out = new Lists();
        Set<String> ids = new HashSet<>(), series = new HashSet<>();
        // 1. In the middle of (a movie, an episode), the newest first; then the next episode of the shows you watch.
        JSONArray resume = items(get(key, "/Items?userId=" + user + "&Recursive=true&Filters=IsResumable"
                + "&IncludeItemTypes=Movie,Episode&SortBy=DatePlayed&SortOrder=Descending&Limit=" + PROGRESS_MAX + FIELDS));
        for (int i = 0; i < resume.length(); i++) {
            JSONObject o = resume.getJSONObject(i);
            boolean episode = "Episode".equals(o.optString("Type"));
            String tag = episode ? Ui.trf("Continue: S{s} E{e}", "s", o.optInt("ParentIndexNumber"), "e", o.optInt("IndexNumber"))
                    : Ui.tr("Continue watching");
            add(out.progress, ids, series, o, client, tag, Recs.PROGRESS, PROGRESS_MAX);
        }
        JSONArray next = items(get(key, "/Shows/NextUp?userId=" + user + "&Limit=" + PROGRESS_MAX + "&enableResumable=false" + FIELDS));
        for (int i = 0; i < next.length(); i++) {
            JSONObject o = next.getJSONObject(i);
            String tag = Ui.trf("Up next: S{s} E{e}", "s", o.optInt("ParentIndexNumber"), "e", o.optInt("IndexNumber"));
            add(out.progress, ids, series, o, client, tag, Recs.PROGRESS, PROGRESS_MAX);
        }
        // 2. Movies: Jellyfin's recommendations ("because you watched…"), one of each category in turn.
        try {
            JSONArray cats = new JSONArray(get(key, "/Movies/Recommendations?userId=" + user + "&categoryLimit=4&itemLimit=6" + FIELDS));
            for (int round = 0; round < 6 && out.movies.size() < RECS_MAX; round++) {
                for (int i = 0; i < cats.length(); i++) {
                    JSONArray items = cats.getJSONObject(i).optJSONArray("Items");
                    if (items != null && round < items.length()) add(out.movies, ids, series, items.getJSONObject(round), client, null, Recs.LIKED, RECS_MAX);
                }
            }
        } catch (Exception e) {
            AppLog.w("jellyfin: movie recommendations: " + e.getMessage());
        }
        // 3. Shows: like the ones you watched last (Jellyfin has no show recommendations of its own).
        try {
            JSONArray played = items(get(key, "/Items?userId=" + user + "&Recursive=true&IncludeItemTypes=Episode&Filters=IsPlayed"
                    + "&SortBy=DatePlayed&SortOrder=Descending&Limit=20&EnableTotalRecordCount=false"));
            List<String> recent = new ArrayList<>();
            for (int i = 0; i < played.length() && recent.size() < 2; i++) {
                String s = played.getJSONObject(i).optString("SeriesId");
                if (!s.isEmpty() && !recent.contains(s)) recent.add(s);
            }
            for (String s : recent) {
                JSONArray similar = items(get(key, "/Items/" + URLEncoder.encode(s, "UTF-8") + "/Similar?userId=" + user + "&Limit=8" + FIELDS));
                for (int i = 0; i < similar.length(); i++) {
                    if ("Series".equals(similar.getJSONObject(i).optString("Type"))) add(out.shows, ids, series, similar.getJSONObject(i), client, null, Recs.LIKED, RECS_MAX);
                }
            }
        } catch (Exception e) {
            AppLog.w("jellyfin: similar shows: " + e.getMessage());
        }
        // 4. Short of those (a new server, little watched yet): the newest you haven't watched.
        String[][] kinds = {{"Movie", "movies"}, {"Series", "shows"}};
        for (String[] k : kinds) {
            List<Recs.Pick> list = k[0].equals("Movie") ? out.movies : out.shows;
            if (list.size() >= RECS_MAX) continue;
            JSONArray latest = items(get(key, "/Items?userId=" + user + "&Recursive=true&IncludeItemTypes=" + k[0]
                    + "&Filters=IsUnplayed&SortBy=DateCreated&SortOrder=Descending&Limit=8" + FIELDS));
            for (int i = 0; i < latest.length(); i++) add(list, ids, series, latest.getJSONObject(i), client, null, Recs.LIKED, RECS_MAX);
        }
        return out;
    }

    /** The user whose library it is: jellyfin_user by name (needed with an API key, which has no user), else the token's own. */
    private static String userId(String key) throws Exception {
        String name = Config.JELLYFIN_USER.trim();
        if (name.isEmpty()) {
            try {
                return new JSONObject(get(key, "/Users/Me")).getString("Id");
            } catch (IOException e) {
                throw new IOException("set jellyfin_user in config.properties (an API key belongs to no user): " + e.getMessage());
            }
        }
        JSONArray users = new JSONArray(get(key, "/Users"));
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            if (name.equalsIgnoreCase(u.optString("Name"))) return u.getString("Id");
        }
        throw new IOException("no Jellyfin user named “" + name + "”");
    }

    private static JSONArray items(String json) throws Exception {
        JSONArray a = new JSONObject(json).optJSONArray("Items");
        return a == null ? new JSONArray() : a;
    }

    /** The item as a pick, into `out` (up to `max`): not watched, not already there, one per show, with a backdrop. */
    private static void add(List<Recs.Pick> out, Set<String> ids, Set<String> series, JSONObject o, String client,
                            String tag, int mine, int max) {
        if (out.size() >= max) return;
        String id = o.optString("Id");
        if (id.isEmpty() || ids.contains(id)) return;
        JSONObject data = o.optJSONObject("UserData");
        if (mine != Recs.PROGRESS && data != null && data.optBoolean("Played")) return;
        String type = o.optString("Type");
        boolean episode = "Episode".equals(type);
        if (!episode && !"Movie".equals(type) && !"Series".equals(type)) return;
        String show = episode ? o.optString("SeriesId") : "Series".equals(type) ? id : "";
        if (!show.isEmpty() && series.contains(show)) return;
        String backdrop = backdrop(o, episode);
        if (backdrop == null) return;
        Recs.Pick p = new Recs.Pick();
        p.jellyfin = true;
        p.id = -(1 + (id.hashCode() & 0x3fffffff));   // negative: never a TMDB number (no Watchmode lookup)
        p.kind = "Movie".equals(type) ? "movie" : "tv";
        p.title = episode ? o.optString("SeriesName", o.optString("Name")) : o.optString("Name");
        p.backdrop = backdrop;
        p.poster = poster(o, episode);
        p.app = "jellyfin";
        p.pkg = client;
        p.link = link(client, id);
        p.tag = tag;
        p.mine = mine;
        List<String> parts = new ArrayList<>();
        if (tag != null) parts.add(tag);
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        JSONArray genres = o.optJSONArray("Genres");
        if (genres != null && genres.length() > 0) parts.add(Recs.genre(genres.optString(0)));
        String length = "Movie".equals(type) ? Recs.runtime((int) (o.optLong("RunTimeTicks") / 600_000_000L))
                : "Series".equals(type) ? Recs.seasons(o.optInt("ChildCount")) : null;
        if (length != null) parts.add(length);
        parts.add("Jellyfin");
        p.meta = String.join(" · ", parts);
        p.detailed = true;
        ids.add(id);
        if (!show.isEmpty()) series.add(show);
        out.add(p);
    }

    /** The landscape image as a server path: the item's backdrop, a show's for an episode, else a thumb or the still. */
    private static String backdrop(JSONObject o, boolean episode) {
        String id = o.optString("Id");
        JSONArray own = o.optJSONArray("BackdropImageTags");
        if (!episode && own != null && own.length() > 0) return image(id, "Backdrop/0", own.optString(0));
        JSONArray parent = o.optJSONArray("ParentBackdropImageTags");
        String parentId = o.optString("ParentBackdropItemId");
        if (parent != null && parent.length() > 0 && !parentId.isEmpty()) return image(parentId, "Backdrop/0", parent.optString(0));
        JSONObject tags = o.optJSONObject("ImageTags");
        if (tags != null && !tags.optString("Thumb").isEmpty()) return image(id, "Thumb", tags.optString("Thumb"));
        if (episode && tags != null && !tags.optString("Primary").isEmpty()) return image(id, "Primary", tags.optString("Primary"));
        return null;
    }

    /** The portrait image: the movie's or show's own, a show's for an episode; null for none. */
    private static String poster(JSONObject o, boolean episode) {
        if (episode) {
            String tag = o.optString("SeriesPrimaryImageTag"), seriesId = o.optString("SeriesId");
            return tag.isEmpty() || seriesId.isEmpty() ? null : image(seriesId, "Primary", tag);
        }
        JSONObject tags = o.optJSONObject("ImageTags");
        String tag = tags == null ? "" : tags.optString("Primary");
        return tag.isEmpty() ? null : image(o.optString("Id"), "Primary", tag);
    }

    /** "/Items/<id>/Images/Backdrop/0?tag=<tag>": the tag changes with the image, so a new image is a new file. */
    private static String image(String id, String type, String tag) {
        return "/Items/" + id.replaceAll("[^A-Za-z0-9-]", "") + "/Images/" + type + "?tag=" + tag.replaceAll("[^A-Za-z0-9-]", "");
    }

    // MARK: - The server

    /** An image of a pick (its backdrop or poster path), at about the size the cards draw it. */
    static HttpURLConnection image(Context c, String path, boolean poster) throws IOException {
        String key = Secrets.get(c, Secrets.JELLYFIN_KEY);
        if (key.isEmpty()) throw new IOException("no jellyfin_api_key on this TV");
        return open(key, path + (poster ? "&maxHeight=750" : "&maxWidth=1920") + "&quality=90");
    }

    private static HttpURLConnection open(String key, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.JELLYFIN_URL.trim().replaceAll("/+$", "") + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);   // custom headers go along across hosts: the key must not follow a redirect
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "MediaBrowser Client=\"Free My TV\", Device=\"TV\", DeviceId=\"io.freemytv\", "
                + "Version=\"1\", Token=\"" + key + "\"");
        return c;
    }

    private static String get(String key, String path) throws IOException {
        HttpURLConnection c = open(key, path);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Jellyfin refused the key (HTTP 401)");
            if (code != 200) throw new IOException("Jellyfin returned HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
