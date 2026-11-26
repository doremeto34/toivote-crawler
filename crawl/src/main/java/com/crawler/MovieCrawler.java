package com.crawler;

import com.model.Movie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crawls a single movie detail page from toivote.com.
 *
 * <h2>Strategy</h2>
 * <ol>
 * <li><b>JSON-LD</b> ({@code <script type="application/ld+json">}) –
 * toivote.com embeds Schema.org Movie markup that contains
 * {@code name}, {@code dateCreated}, {@code genre[]},
 * {@code director[].name}, and {@code actor[].name}.
 * This is the most reliable source.</li>
 * <li><b>DL metadata block</b> ({@code 
 * <dl>
 * …
 * <dt>…
 * <dd>}) –
 * the page renders a "Thông tin nhanh" section whose
 * {@code 
 * <dt>} labels include "Đất nước" and "Năm sản xuất".</li>
 * <li><b>OG / fallbacks</b> – for the title if JSON-LD is absent.</li>
 * </ol>
 */
public class MovieCrawler {

  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
      "AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/120.0.0.0 Safari/537.36";

  private static final int TIMEOUT_MS = 20_000;

  // Matches JSON string values like "name":"Dinna Jasanti"
  private static final Pattern NAME_RE = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
  // Extracts individual genre strings from the genre array: "genre":["Kinh
  // dị","Hành động"]
  private static final Pattern GENRE_ITEM_RE = Pattern.compile("\"([^\"]+)\"");

  public Movie crawl(String url) {
    try {
      Document doc = Jsoup.connect(url)
          .userAgent(USER_AGENT)
          .timeout(TIMEOUT_MS)
          .followRedirects(true)
          .get();

      Movie movie = new Movie();
      movie.setUrl(url);

      // ── 1. JSON-LD (most reliable) ────────────────────────────────
      Element ldScript = doc.selectFirst("script[type=application/ld+json]");
      if (ldScript != null) {
        String json = ldScript.html();
        // Guard: only process the Movie schema block
        if (json.contains("\"@type\":\"Movie\"")) {
          movie.setTitle(extractJsonString(json, "name"));
          movie.setYear(extractJsonString(json, "dateCreated"));
          movie.setGenres(extractJsonStringArray(json, "genre"));
          movie.setDirectors(extractPersonList(json, "director"));
          movie.setActors(extractPersonList(json, "actor"));
        }
      }

      // ── 2. <dl> metadata block ────────────────────────────────────
      // Structure: <dt>Năm sản xuất</dt><dd>2026</dd>
      // <dt>Đất nước</dt><dd>Indonesia</dd>
      // <dt>Thể loại</dt><dd><span><a>Kinh dị</a></span></dd>
      Elements dtList = doc.select("dl dt");
      for (Element dt : dtList) {
        String label = dt.text().trim();
        Element dd = dt.nextElementSibling();
        if (dd == null)
          continue;

        switch (label) {
          case "Năm sản xuất" -> {
            if (movie.getYear() == null || movie.getYear().isBlank())
              movie.setYear(dd.text().trim());
          }
          case "Đất nước" -> movie.setCountry(dd.text().trim());
          case "Thể loại" -> {
            if (movie.getGenres() == null || movie.getGenres().isEmpty()) {
              List<String> genres = new ArrayList<>();
              for (Element a : dd.select("a"))
                genres.add(a.text().trim());
              if (genres.isEmpty())
                genres.add(dd.text().trim());
              movie.setGenres(genres);
            }
          }
        }
      }

      // ── 3. Title fallback ─────────────────────────────────────────
      if (movie.getTitle() == null || movie.getTitle().isBlank()) {
        Element h1 = doc.selectFirst("h1[itemprop=name], h1");
        if (h1 != null)
          movie.setTitle(h1.text().trim());
      }
      if (movie.getTitle() == null || movie.getTitle().isBlank()) {
        Element meta = doc.selectFirst("meta[property=og:title]");
        if (meta != null) {
          // Strip " | ToiVote" suffix
          String t = meta.attr("content").replaceAll("\\s*\\|\\s*ToiVote$", "").trim();
          // Also strip trailing "(YYYY)" – we keep it in year field
          t = t.replaceAll("\\s*\\(\\d{4}\\)$", "").trim();
          movie.setTitle(t);
        }
      }

      // ── 4. Year fallback from og:title ────────────────────────────
      if (movie.getYear() == null || movie.getYear().isBlank()) {
        Element meta = doc.selectFirst("meta[property=og:title]");
        if (meta != null) {
          Matcher m = Pattern.compile("\\((\\d{4})\\)").matcher(meta.attr("content"));
          if (m.find())
            movie.setYear(m.group(1));
        }
      }

      System.out.printf("[CRAWL] ✓ %-40s (%s) | %s%n",
          movie.getTitle(), movie.getYear(), url);
      return movie;

    } catch (IOException e) {
      System.err.printf("[CRAWL] ✗ Failed → %s%n  Reason: %s%n", url, e.getMessage());
      return null;
    }
  }

  // ─── JSON-LD helpers ─────────────────────────────────────────────────────

  /**
   * Extracts a simple string value: {@code "key":"value"}.
   */
  private String extractJsonString(String json, String key) {
    Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
    Matcher m = p.matcher(json);
    return m.find() ? m.group(1) : null;
  }

  /**
   * Extracts a JSON string array: {@code "genre":["A","B"]}.
   */
  private List<String> extractJsonStringArray(String json, String key) {
    List<String> result = new ArrayList<>();
    Pattern arrayPat = Pattern.compile(
        "\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^]]*)]");
    Matcher am = arrayPat.matcher(json);
    if (!am.find())
      return result;
    String inner = am.group(1);
    Matcher im = GENRE_ITEM_RE.matcher(inner);
    while (im.find()) {
      String v = im.group(1).trim();
      if (!v.isBlank())
        result.add(v);
    }
    return result;
  }

  /**
   * Extracts a list of person names from an object array:
   * {@code "director":[{"@type":"Person","name":"John"}]}.
   */
  private List<String> extractPersonList(String json, String role) {
    List<String> result = new ArrayList<>();
    // Find the array for this role
    Pattern arrayPat = Pattern.compile(
        "\"" + Pattern.quote(role) + "\"\\s*:\\s*\\[([^]]+)]");
    Matcher am = arrayPat.matcher(json);
    if (!am.find())
      return result;
    String inner = am.group(1);
    // Within each object extract "name":"..."
    Matcher nm = NAME_RE.matcher(inner);
    while (nm.find()) {
      String name = nm.group(1).trim();
      if (!name.isBlank())
        result.add(name);
    }
    return result;
  }
}
