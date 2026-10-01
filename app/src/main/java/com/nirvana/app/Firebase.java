package com.nirvana.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to NIRVANA's Firebase project over its plain web API: email + password login,
 * and reading and writing the league's database. Every method blocks, so call it off the main thread.
 */
public class Firebase {

    static final String API_KEY = "AIzaSyC_-YG21tRDjEU6MSbP1nUOnh6u58ULApg";
    static final String PROJECT = "nirvana-2e2d1";
    static final String DOCS = "https://firestore.googleapis.com/v1/projects/" + PROJECT + "/databases/(default)/documents/";

    /** A failure the person can act on. */
    public static class Problem extends Exception {
        public final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
    }

    public static class Profile {
        public String uid, name;
        public int baseMin, baseOpens;
    }

    private final SharedPreferences prefs;

    public Firebase(SharedPreferences prefs) { this.prefs = prefs; }

    public boolean signedIn() { return prefs.getString("fb_refresh", null) != null; }
    public String uid() { return prefs.getString("fb_uid", null); }
    public String email() { return prefs.getString("fb_email", ""); }

    public void signOut() {
        prefs.edit().remove("fb_refresh").remove("fb_id").remove("fb_exp").remove("fb_uid").remove("fb_email").apply();
    }

    // ---------- login ----------

    public void signIn(String email, String password) throws Problem {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            body.put("returnSecureToken", true);
            Resp r = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + API_KEY,
                    "application/json", body.toString(), null);
            if (r.code != 200) throw new Problem(r.code, authMessage(r.body));
            JSONObject j = new JSONObject(r.body);
            saveTokens(j.getString("idToken"), j.getString("refreshToken"), j.optString("expiresIn", "3600"),
                    j.getString("localId"), j.optString("email", email));
        } catch (JSONException e) {
            throw new Problem(0, "Unexpected reply from the server. Try again.");
        } catch (IOException e) {
            throw new Problem(0, "Couldn't reach NIRVANA. Check your connection.");
        }
    }

    private void saveTokens(String id, String refresh, String expiresIn, String uid, String email) {
        long exp = System.currentTimeMillis() + (parseLong(expiresIn, 3600) - 120) * 1000L;
        prefs.edit().putString("fb_id", id).putString("fb_refresh", refresh).putLong("fb_exp", exp)
                .putString("fb_uid", uid).putString("fb_email", email).apply();
    }

    private String token() throws Problem {
        String id = prefs.getString("fb_id", null);
        if (id != null && System.currentTimeMillis() < prefs.getLong("fb_exp", 0)) return id;
        String refresh = prefs.getString("fb_refresh", null);
        if (refresh == null) throw new Problem(401, "Sign in again.");
        try {
            String form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8");
            Resp r = http("POST", "https://securetoken.googleapis.com/v1/token?key=" + API_KEY,
                    "application/x-www-form-urlencoded", form, null);
            if (r.code != 200) {
                if (r.code == 400) { signOut(); throw new Problem(401, "Your login expired. Sign in again."); }
                throw new Problem(r.code, "Couldn't reach NIRVANA. Check your connection.");
            }
            JSONObject j = new JSONObject(r.body);
            saveTokens(j.getString("id_token"), j.getString("refresh_token"), j.optString("expires_in", "3600"),
                    j.getString("user_id"), email());
            return j.getString("id_token");
        } catch (JSONException | IOException e) {
            throw new Problem(0, "Couldn't reach NIRVANA. Check your connection.");
        }
    }

    // ---------- database ----------

    /** Your profile, or null if this account hasn't joined the league on the website yet. */
    public Profile myProfile() throws Problem {
        Resp r = get("users/" + uid());
        if (r.code == 404 || r.code == 403) return null;
        check(r);
        try {
            return toProfile(new JSONObject(r.body));
        } catch (JSONException e) {
            throw new Problem(0, "Unexpected reply from the server. Try again.");
        }
    }

    /** Every member, by account id. */
    public Map<String, Profile> members() throws Problem {
        Resp r = get("users?pageSize=100");
        check(r);
        Map<String, Profile> out = new LinkedHashMap<>();
        try {
            JSONArray docs = new JSONObject(r.body).optJSONArray("documents");
            if (docs == null) return out;
            for (int i = 0; i < docs.length(); i++) {
                Profile p = toProfile(docs.getJSONObject(i));
                out.put(p.uid, p);
            }
        } catch (JSONException e) {
            throw new Problem(0, "Unexpected reply from the server. Try again.");
        }
        return out;
    }

    /** One member's logged day as plain values, or null when they haven't logged it. */
    public Map<String, Object> day(String memberUid, String date) throws Problem {
        Resp r = get("users/" + memberUid + "/days/" + date);
        if (r.code == 404) return null;
        check(r);
        try {
            return fromFields(new JSONObject(r.body).optJSONObject("fields"));
        } catch (JSONException e) {
            throw new Problem(0, "Unexpected reply from the server. Try again.");
        }
    }

    /**
     * Saves only the given fields of your day, so anything else you added on the website
     * (like your one-line takeaway) is kept.
     */
    public void saveDay(String date, Map<String, Object> values) throws Problem {
        try {
            JSONObject fields = new JSONObject();
            JSONArray paths = new JSONArray();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                fields.put(e.getKey(), toValue(e.getValue()));
                paths.put(e.getKey());
            }
            JSONObject update = new JSONObject();
            update.put("name", "projects/" + PROJECT + "/databases/(default)/documents/users/" + uid() + "/days/" + date);
            update.put("fields", fields);
            JSONObject mask = new JSONObject();
            mask.put("fieldPaths", paths);
            JSONObject write = new JSONObject();
            write.put("update", update);
            write.put("updateMask", mask);
            JSONObject body = new JSONObject();
            body.put("writes", new JSONArray().put(write));
            // A masked update creates the day if it's new and leaves other fields alone if it exists.
            Resp r = http("POST", DOCS.substring(0, DOCS.length() - 1) + ":commit", "application/json", body.toString(), token());
            check(r);
        } catch (JSONException e) {
            throw new Problem(0, "Couldn't prepare your day. Try again.");
        } catch (IOException e) {
            throw new Problem(0, "Couldn't reach NIRVANA. Check your connection.");
        }
    }

    // ---------- helpers ----------

    private Resp get(String path) throws Problem {
        try {
            return http("GET", DOCS + path, null, null, token());
        } catch (IOException e) {
            throw new Problem(0, "Couldn't reach NIRVANA. Check your connection.");
        }
    }

    private static void check(Resp r) throws Problem {
        if (r.code >= 200 && r.code < 300) return;
        if (r.code == 401) throw new Problem(401, "Your login expired. Sign in again.");
        if (r.code == 403) throw new Problem(403, "NIRVANA refused that. Make sure you've joined the league on the website.");
        throw new Problem(r.code, "NIRVANA had a problem (" + r.code + "). Try again in a minute.");
    }

    private static Profile toProfile(JSONObject doc) throws JSONException {
        Profile p = new Profile();
        String name = doc.getString("name");
        p.uid = name.substring(name.lastIndexOf('/') + 1);
        Map<String, Object> f = fromFields(doc.optJSONObject("fields"));
        Object n = f.get("name");
        p.name = n == null ? "Someone" : n.toString();
        p.baseMin = asInt(f.get("baseMin"));
        p.baseOpens = asInt(f.get("baseOpens"));
        return p;
    }

    static Map<String, Object> fromFields(JSONObject fields) throws JSONException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fields == null) return out;
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject v = fields.getJSONObject(k);
            if (v.has("integerValue")) out.put(k, parseLong(v.getString("integerValue"), 0));
            else if (v.has("doubleValue")) out.put(k, v.getDouble("doubleValue"));
            else if (v.has("booleanValue")) out.put(k, v.getBoolean("booleanValue"));
            else if (v.has("stringValue")) out.put(k, v.getString("stringValue"));
        }
        return out;
    }

    static JSONObject toValue(Object o) throws JSONException {
        JSONObject v = new JSONObject();
        if (o instanceof Boolean) v.put("booleanValue", o);
        else if (o instanceof Integer || o instanceof Long) v.put("integerValue", String.valueOf(o));
        else if (o instanceof Number) v.put("doubleValue", ((Number) o).doubleValue());
        else v.put("stringValue", String.valueOf(o));
        return v;
    }

    static int asInt(Object o) { return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : 0; }
    static boolean asBool(Object o) { return o instanceof Boolean && (Boolean) o; }

    static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static String authMessage(String body) {
        String m = "";
        try { m = new JSONObject(body).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
        if (m.startsWith("INVALID_LOGIN_CREDENTIALS") || m.startsWith("INVALID_PASSWORD") || m.startsWith("EMAIL_NOT_FOUND"))
            return "That email and password don't match.";
        if (m.startsWith("INVALID_EMAIL")) return "That doesn't look like an email address.";
        if (m.startsWith("TOO_MANY_ATTEMPTS")) return "Too many attempts. Wait a few minutes and try again.";
        if (m.startsWith("USER_DISABLED")) return "This account has been turned off.";
        return "Couldn't sign in. Check your connection and try again.";
    }

    static class Resp {
        int code;
        String body;
    }

    private static Resp http(String method, String url, String contentType, String body, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream is = r.code < 400 ? c.getInputStream() : c.getErrorStream();
        r.body = is == null ? "" : read(is);
        c.disconnect();
        return r;
    }

    private static String read(InputStream is) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }
}
