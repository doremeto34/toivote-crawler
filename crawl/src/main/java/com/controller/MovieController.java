package com.controller;

import com.db.DatabaseManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller xử lý request cho Webservice (Bài 5: Sử dụng Caffeine Cache).
 */
public class MovieController implements HttpHandler {

  private static final Pattern TOIVOTE_PATH_RE = Pattern.compile("^/movie/(https?://.+)$");
  private final DatabaseManager db;
  private final Cache<String, String> cache;

  public MovieController(DatabaseManager db, Cache<String, String> cache) {
    this.db = db;
    this.cache = cache;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if (!"GET".equalsIgnoreCase(method)) {
      sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
      return;
    }

    String url = extractUrl(exchange);
    if (url == null || url.isBlank()) {
      String help = """
          {
            "error": "Vui long truyen url phim can lay",
            "cach_1": "http://localhost:8080/movie?url=https://toivote.com/movie/0552171e-0d2d-4763-979d-6276a5d882f3",
            "cach_2": "http://localhost:8080/movie/https://toivote.com/movie/0552171e-0d2d-4763-979d-6276a5d882f3"
          }""";
      sendResponse(exchange, 400, help);
      return;
    }

    getMovieByUrl(exchange, url);
  }

  private String extractUrl(HttpExchange exchange) {
    // 1. Kiểm tra qua query param: ?url=https://...
    String query = exchange.getRequestURI().getRawQuery();
    if (query != null && !query.isBlank()) {
      for (String param : query.split("&")) {
        String[] pair = param.split("=", 2);
        if (pair.length == 2 && "url".equalsIgnoreCase(pair[0])) {
          return URLDecoder.decode(pair[1], StandardCharsets.UTF_8).trim();
        }
      }
    }

    // 2. Kiểm tra qua path: /movie/https://...
    String path = exchange.getRequestURI().getPath();
    Matcher m = TOIVOTE_PATH_RE.matcher(path);
    if (m.find()) {
      return m.group(1).trim();
    }

    return null;
  }

  private void getMovieByUrl(HttpExchange exchange, String url) throws IOException {
    // Đọc từ Caffeine Cache
    String movieJson = cache.getIfPresent(url);

    if (movieJson == null) {
      System.out.printf("[CACHE MISS] (Hit rate: %.1f%%) -> %s%n", cache.stats().hitRate() * 100, url);
      movieJson = db.getMovieByUrlAsJson(url);
      if (movieJson != null) {
        cache.put(url, movieJson);
        System.out.println("[CACHE STORED] Da luu vao Caffeine Cache: " + url);
      }
    } else {
      System.out.printf("[CACHE HIT!] (Hit rate: %.1f%%) -> %s%n", cache.stats().hitRate() * 100, url);
    }

    int status = (movieJson == null) ? 404 : 200;
    String response = (movieJson == null) ? "{\"error\":\"Movie not found\"}" : movieJson;
    sendResponse(exchange, status, response);
  }

  private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
    byte[] response = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
  }
}
