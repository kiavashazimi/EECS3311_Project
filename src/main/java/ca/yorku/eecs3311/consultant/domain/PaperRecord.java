package ca.yorku.eecs3311.consultant.domain;

import java.util.Objects;

/**
 * A paper as the literature sources return it, before any analysis: enough to
 * list it in the feed and fetch it later.
 */
public class PaperRecord {

    private String id = "";
    private String title = "";
    private String venue = "";
    private String date = "";
    private String summary = "";
    private String url = "";
    private String repoUrl = "";

    public PaperRecord() {
    }

    public PaperRecord(String id, String title, String venue, String date) {
        this.id = nullSafe(id);
        this.title = nullSafe(title);
        this.venue = nullSafe(venue);
        this.date = nullSafe(date);
    }

    /** @return true when the paper links a code repository, which F09 requires */
    public boolean hasRepository() {
        return !repoUrl.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = nullSafe(id);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = nullSafe(title);
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = nullSafe(venue);
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = nullSafe(date);
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = nullSafe(summary);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = nullSafe(url);
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = nullSafe(repoUrl);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaperRecord other)) {
            return false;
        }
        return id.equals(other.id) && title.equals(other.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title);
    }

    @Override
    public String toString() {
        return title + (venue.isBlank() ? "" : " (" + venue + ")");
    }
}
