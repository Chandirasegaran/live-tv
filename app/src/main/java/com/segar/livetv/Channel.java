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
    /** Other stream links the playlist listed under the same channel id; tried in order when one fails. */
    final java.util.List<Channel> alternates = new java.util.ArrayList<>();

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

    void addAlternate(Channel c) { alternates.add(c); }

    /** How many playable links this channel has: itself plus the alternates. */
    int variants() { return 1 + alternates.size(); }

    /** The n-th playable link: this one first, then the alternates; null past the end. */
    Channel variant(int n) {
        if (n <= 0) return this;
        return n - 1 < alternates.size() ? alternates.get(n - 1) : null;
    }

    boolean inGroup(String g) {
        for (String x : groups) if (x.equals(g)) return true;
        return false;
    }
}
