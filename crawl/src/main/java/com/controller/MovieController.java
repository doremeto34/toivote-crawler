package com.controller;

import com.db.DatabaseManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class MovieController implements HttpHandler {

  private static final Pattern TOIVOTE_RE = Pattern
      .compile("^/movie/https://toivote\\.com/movie/[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");
  private final DatabaseManager db;
  private final Cache<String, String> cache;

  public MovieController(DatabaseManager db, Cache<String, String> cache) {
    this.db = db;
    this.cache = cache;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();

    if (method.equals("GET") && TOIVOTE_RE.matcher(path).matches()) {
      getMovieByUrl(exchange);
    }
  }

  private void getMovieByUrl(HttpExchange exchange) throws IOException {
    String url = exchange.getRequestURI().getPath().substring("/movie/".length());
    String movieJson = cache.getIfPresent(url);
    if (movieJson == null) {
      System.out.println("[CACHE MISS] " + url);
      movieJson = db.getMovieByUrlAsJson(url);
      if (movieJson != null) {
        cache.put(url, movieJson);
      }
    } else {
      System.out.println("[CACHE HIT] " + url);
    }
    int status;
    byte[] response;
    if (movieJson == null) {
      status = 404;
      response = "{\"error\":\"Movie not found\"}".getBytes(StandardCharsets.UTF_8);
    } else {
      status = 200;
      response = movieJson.getBytes(StandardCharsets.UTF_8);
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
  }
}
