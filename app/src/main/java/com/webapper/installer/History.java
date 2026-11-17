package com.webapper.installer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Remembers the apps this installer has created (shown in the "Your apps" list). */
final class History {
    private static final String PREFS = "history";

    static final class Item {
        String name, url, packageName;
    }

    private History() {}

    static synchronized void add(Context ctx, Generator.Result r, String url, List<String> extensions) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray old = new JSONArray(sp.getString("apps", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                if (!old.getJSONObject(i).optString("package").equals(r.packageName)) out.put(old.get(i));
            }
            out.put(new JSONObject().put("name", r.name).put("url", url).put("package", r.packageName)
                    .put("extensions", new JSONArray(extensions)));
            sp.edit().putString("apps", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized List<Item> list(Context ctx) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("apps", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.name = o.optString("name");
                it.url = o.optString("url");
                it.packageName = o.optString("package");
                items.add(it);
            }
        } catch (Exception ignored) {
        }
        return items;
    }
}
