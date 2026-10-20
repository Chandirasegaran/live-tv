package com.segar.livetv;

/** One playlist entry. {@link #key} is the tvg-id without its @feed suffix, or the URL if there's no id. */
final class Channel {
    final String key;
    final String name;
    final String logo;
    final String[] groups;
    final String url;
    final String referrer;
    final String userAgent;
    final String search;
    int number;

    Channel(String key, String name, String logo, String[] groups, String url, String referrer, String userAgent) {
        this.key = key;
        this.name = name;
        this.logo = logo;
        this.groups = groups;
        this.url = url;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.search = name.toLowerCase(java.util.Locale.ROOT);
    }

    boolean inGroup(String g) {
        for (String x : groups) if (x.equals(g)) return true;
        return false;
    }
}
