package com.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamically discovers movie URLs from toivote.com by exploring:
 * - The genres overview page: /discover/genres
 * - Specific genre pages: /genre/{slug} (e.g., /genre/action, /genre/horror,
 * etc.)
 * - The homepage: /
 *
 * Each discovered URL is guaranteed to follow the /movie/{uuid} format.
 */
public class MovieDiscovery {

  private static final String BASE_URL = "https://toivote.com";
  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
      "AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/120.0.0.0 Safari/537.36";
  private static final int TIMEOUT_MS = 20_000;

  // Pattern matching UUID movie URLs: /movie/12345678-1234-1234-1234-123456789abc
  private static final Pattern MOVIE_UUID_PATTERN = Pattern
      .compile("/movie/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  // Pattern matching genre slugs: /genre/{slug}
  private static final Pattern GENRE_SLUG_PATTERN = Pattern.compile("/genre/([a-z0-9-]+)");

  /**
   * Discovers all available genre slugs from https://toivote.com/discover/genres.
   * Returns a sorted list of unique slugs (e.g. ["action", "comedy", "horror",
   * ...]).
   */
  public List<String> discoverGenreSlugs() {
    Set<String> slugs = new LinkedHashSet<>();
    String url = BASE_URL + "/discover/genres";
    System.out.println("[DISCOVERY] Scanning genres at: " + url);

    try {
      Document doc = Jsoup.connect(url)
          .userAgent(USER_AGENT)
          .timeout(TIMEOUT_MS)
          .get();

      Matcher m = GENRE_SLUG_PATTERN.matcher(doc.html());
      while (m.find()) {
        slugs.add(m.group(1));
      }
    } catch (IOException e) {
      System.err.println("[DISCOVERY] Failed to fetch genres page: " + e.getMessage());
    }

    // Fallback default genres if scanning fails
    if (slugs.isEmpty()) {
      slugs.addAll(List.of(
          "action", "adventure", "animation", "comedy", "crime",
          "drama", "family", "fantasy", "horror", "mystery",
          "romance", "sci-fi", "thriller", "war"));
    }

    System.out.printf("[DISCOVERY] Found %d genre categories: %s%n", slugs.size(), slugs);
    return new ArrayList<>(slugs);
  }

  /**
   * Crawls movie URLs for a specific genre, e.g. /genre/action.
   *
   * @param genreSlug the slug (e.g., "action", "horror")
   * @param maxCount  maximum URLs to collect from this category
   * @return list of full movie URLs
   */
  public List<String> discoverByGenre(String genreSlug, int maxCount) {
    String genreUrl = BASE_URL + "/genre/" + genreSlug;
    Set<String> movieUrls = new LinkedHashSet<>();
    System.out.printf("[DISCOVERY] Scanning category: %s (%s)%n", genreSlug, genreUrl);

    try {
      Document doc = Jsoup.connect(genreUrl)
          .userAgent(USER_AGENT)
          .timeout(TIMEOUT_MS)
          .get();

      // Extract via standard HTML anchors
      Elements links = doc.select("a[href*=/movie/]");
      for (Element a : links) {
        String href = a.attr("href");
        Matcher m = MOVIE_UUID_PATTERN.matcher(href);
        if (m.find()) {
          movieUrls.add(BASE_URL + "/movie/" + m.group(1));
          if (movieUrls.size() >= maxCount)
            break;
        }
      }

      // Also scan raw HTML text in case Next.js hydration payload holds more links
      if (movieUrls.size() < maxCount) {
        Matcher m = MOVIE_UUID_PATTERN.matcher(doc.html());
        while (m.find()) {
          movieUrls.add(BASE_URL + "/movie/" + m.group(1));
          if (movieUrls.size() >= maxCount)
            break;
        }
      }

      System.out.printf("[DISCOVERY] -> Found %d movies for genre '%s'%n", movieUrls.size(), genreSlug);

    } catch (IOException e) {
      System.err.printf("[DISCOVERY] Error fetching genre '%s': %s%n", genreSlug, e.getMessage());
    }

    return new ArrayList<>(movieUrls);
  }

  /**
   * Discovers movie URLs dynamically across genres until targetCount is reached
   * (~100 URLs).
   *
   * Strategy:
   * 1. Fetch /discover/genres overview (contains multiple movie cards).
   * 2. Fetch popular genre pages (/genre/action, /genre/horror, etc.)
   * sequentially
   * until the desired target count is reached.
   * 3. Return deduplicated list.
   *
   * @param targetCount desired number of movie URLs (e.g. 100)
   * @return list of distinct movie URLs
   */
  public List<String> discoverMovies(int targetCount) {
    Set<String> collectedUrls = new LinkedHashSet<>();
    System.out.printf("[DISCOVERY] Starting discovery for ~%d movie URLs...%n", targetCount);

    // 1. First, scan /discover/genres overview page
    try {
      Document doc = Jsoup.connect(BASE_URL + "/discover/genres")
          .userAgent(USER_AGENT)
          .timeout(TIMEOUT_MS)
          .get();

      Matcher m = MOVIE_UUID_PATTERN.matcher(doc.html());
      while (m.find()) {
        collectedUrls.add(BASE_URL + "/movie/" + m.group(1));
        if (collectedUrls.size() >= targetCount) {
          System.out.printf("[DISCOVERY] Reached %d URLs directly from /discover/genres!%n", collectedUrls.size());
          return new ArrayList<>(collectedUrls);
        }
      }
    } catch (IOException e) {
      System.err.println("[DISCOVERY] Error scanning /discover/genres: " + e.getMessage());
    }

    // 2. Next, crawl across individual genre pages until reaching targetCount
    List<String> genres = discoverGenreSlugs();
    for (String genre : genres) {
      if (collectedUrls.size() >= targetCount)
        break;

      int needed = targetCount - collectedUrls.size();
      List<String> genreMovies = discoverByGenre(genre, Math.max(needed, 30));
      collectedUrls.addAll(genreMovies);

      System.out.printf("[DISCOVERY] Total collected so far: %d / %d%n", collectedUrls.size(), targetCount);

      // Small polite pause between genre pages
      try {
        Thread.sleep(500);
      } catch (InterruptedException ignored) {
      }
    }

    // 3. Fallback to homepage if still needed
    if (collectedUrls.size() < targetCount) {
      try {
        Document doc = Jsoup.connect(BASE_URL)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get();
        Matcher m = MOVIE_UUID_PATTERN.matcher(doc.html());
        while (m.find()) {
          collectedUrls.add(BASE_URL + "/movie/" + m.group(1));
          if (collectedUrls.size() >= targetCount)
            break;
        }
      } catch (IOException ignored) {
      }
    }

    System.out.printf("[DISCOVERY] Final discovery completed: %d unique URLs ready to crawl.%n", collectedUrls.size());
    return new ArrayList<>(collectedUrls);
  }
}
