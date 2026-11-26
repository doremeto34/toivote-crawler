package com.db;

import com.model.Movie;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages SQLite database operations for storing crawled movie data.
 * The database file is stored on disk as a persistent backup at data/movies.db.
 */
public class DatabaseManager {

  private static final String DB_DIR = "data";
  private static final String DB_FILE = DB_DIR + File.separator + "movies.db";
  private static final String BACKUP_JSON_FILE = DB_DIR + File.separator + "movies_backup.json";
  private static final String JDBC_URL = "jdbc:sqlite:" + DB_FILE;

  private Connection connection;

  /**
   * Opens (or creates) the SQLite database and initialises the schema.
   */
  public void init() throws SQLException {
    // Ensure the data directory exists (disk backup location)
    File dir = new File(DB_DIR);
    if (!dir.exists()) {
      dir.mkdirs();
    }

    connection = DriverManager.getConnection(JDBC_URL);
    connection.setAutoCommit(false);

    // Create tables if they don't exist
    try (Statement stmt = connection.createStatement()) {

      // Main movies table
      stmt.execute("""
              CREATE TABLE IF NOT EXISTS movies (
                  id      INTEGER PRIMARY KEY AUTOINCREMENT,
                  url     TEXT    NOT NULL UNIQUE,
                  title   TEXT,
                  year    TEXT,
                  country TEXT,
                  crawled_at DATETIME DEFAULT (datetime('now','localtime'))
              )
          """);

      // Normalised genres (one-to-many)
      stmt.execute("""
              CREATE TABLE IF NOT EXISTS genres (
                  id       INTEGER PRIMARY KEY AUTOINCREMENT,
                  movie_id INTEGER NOT NULL,
                  genre    TEXT,
                  FOREIGN KEY(movie_id) REFERENCES movies(id) ON DELETE CASCADE
              )
          """);

      // Normalised directors (one-to-many)
      stmt.execute("""
              CREATE TABLE IF NOT EXISTS directors (
                  id       INTEGER PRIMARY KEY AUTOINCREMENT,
                  movie_id INTEGER NOT NULL,
                  director TEXT,
                  FOREIGN KEY(movie_id) REFERENCES movies(id) ON DELETE CASCADE
              )
          """);

      // Normalised actors (one-to-many)
      stmt.execute("""
              CREATE TABLE IF NOT EXISTS actors (
                  id       INTEGER PRIMARY KEY AUTOINCREMENT,
                  movie_id INTEGER NOT NULL,
                  actor    TEXT,
                  FOREIGN KEY(movie_id) REFERENCES movies(id) ON DELETE CASCADE
              )
          """);

      connection.commit();
    } catch (SQLException e) {
      connection.rollback();
      throw e;
    }

    System.out.println("[DB] Database initialised at: " + new File(DB_FILE).getAbsolutePath());
  }

  /**
   * Inserts a movie (and its related lists) into the database.
   * Uses INSERT OR REPLACE so re-running is idempotent.
   */
  public void saveMovie(Movie movie) throws SQLException {
    String upsertMovie = """
            INSERT INTO movies (url, title, year, country)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(url) DO UPDATE SET
                title   = excluded.title,
                year    = excluded.year,
                country = excluded.country,
                crawled_at = datetime('now','localtime')
        """;

    try (PreparedStatement ps = connection.prepareStatement(upsertMovie,
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, movie.getUrl());
      ps.setString(2, movie.getTitle());
      ps.setString(3, movie.getYear());
      ps.setString(4, movie.getCountry());
      ps.executeUpdate();

      // Retrieve the rowid of the inserted / updated row
      long movieId;
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (rs.next() && rs.getLong(1) > 0) {
          movieId = rs.getLong(1);
        } else {
          movieId = getMovieIdByUrl(movie.getUrl());
        }
      }

      // Delete old child rows before re-inserting (clean upsert)
      deleteChildren(movieId);

      // Insert child lists
      insertList(movieId, "genres", "genre", movie.getGenres());
      insertList(movieId, "directors", "director", movie.getDirectors());
      insertList(movieId, "actors", "actor", movie.getActors());

      connection.commit();
      System.out.printf("[DB] Saved: %s (%s, %s)%n", movie.getTitle(), movie.getYear(), movie.getCountry());

    } catch (SQLException e) {
      connection.rollback();
      System.err.println("[DB] Error saving movie: " + movie.getUrl() + " -> " + e.getMessage());
      throw e;
    }
  }

  private long getMovieIdByUrl(String url) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT id FROM movies WHERE url = ?")) {
      ps.setString(1, url);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next())
          return rs.getLong("id");
      }
    }
    throw new SQLException("Cannot find movie id for url: " + url);
  }

  private void deleteChildren(long movieId) throws SQLException {
    for (String table : new String[] { "genres", "directors", "actors" }) {
      try (PreparedStatement ps = connection.prepareStatement(
          "DELETE FROM " + table + " WHERE movie_id = ?")) {
        ps.setLong(1, movieId);
        ps.executeUpdate();
      }
    }
  }

  private void insertList(long movieId, String table, String column,
      List<String> items) throws SQLException {
    if (items == null || items.isEmpty())
      return;
    String sql = "INSERT INTO " + table + " (movie_id, " + column + ") VALUES (?, ?)";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      for (String item : items) {
        if (item != null && !item.isBlank()) {
          ps.setLong(1, movieId);
          ps.setString(2, item.trim());
          ps.addBatch();
        }
      }
      ps.executeBatch();
    }
  }

  public int getTotalMoviesCount() {
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM movies")) {
      if (rs.next())
        return rs.getInt(1);
    } catch (SQLException ignored) {
    }
    return 0;
  }


  /**
   * Returns a single movie formatted as pretty JSON by URL (phục vụ Bài 3).
   */
  public String getMovieByUrlAsJson(String url) {
    if (url == null || url.isBlank()) return null;
    String sql = "SELECT id, url, title, year, country, crawled_at FROM movies WHERE url = ? OR url LIKE ?";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, url.trim());
      ps.setString(2, "%" + url.trim() + "%");
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return buildMovieJson(rs);
        }
      }
    } catch (SQLException e) {
      System.err.println("[DB] Error fetching movie by url " + url + ": " + e.getMessage());
    }
    return null;
  }

  private String buildMovieJson(ResultSet rs) throws SQLException {
    long id = rs.getLong("id");
    String url = rs.getString("url");
    String title = rs.getString("title");
    String year = rs.getString("year");
    String country = rs.getString("country");
    String crawledAt = rs.getString("crawled_at");

    List<String> genres = getChildList(id, "genres", "genre");
    List<String> directors = getChildList(id, "directors", "director");
    List<String> actors = getChildList(id, "actors", "actor");

    return String.format("""
      {
        "id": %d,
        "title": %s,
        "year": %s,
        "country": %s,
        "url": %s,
        "crawled_at": %s,
        "genres": %s,
        "directors": %s,
        "actors": %s
      }""",
      id,
      escapeJson(title),
      escapeJson(year),
      escapeJson(country),
      escapeJson(url),
      escapeJson(crawledAt),
      toJsonArray(genres),
      toJsonArray(directors),
      toJsonArray(actors)
    );
  }

  private List<String> getChildList(long movieId, String table, String col) {
    List<String> list = new ArrayList<>();
    String sql = "SELECT " + col + " FROM " + table + " WHERE movie_id = ?";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setLong(1, movieId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next())
          list.add(rs.getString(1));
      }
    } catch (SQLException ignored) {
    }
    return list;
  }

  /**
   * Exports all movies in SQLite to a clean UTF-8 JSON file for human-readable
   * disk backup.
   */
  public void exportToJsonBackup() {
    File file = new File(BACKUP_JSON_FILE);
    String sql = "SELECT id, url, title, year, country, crawled_at FROM movies ORDER BY id ASC";

    try (PrintWriter writer = new PrintWriter(
        new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {

      writer.println("[");
      boolean first = true;
      while (rs.next()) {
        if (!first)
          writer.println(",");
        first = false;

        long id = rs.getLong("id");
        String url = rs.getString("url");
        String title = rs.getString("title");
        String year = rs.getString("year");
        String country = rs.getString("country");
        String crawledAt = rs.getString("crawled_at");

        List<String> genres = getChildList(id, "genres", "genre");
        List<String> directors = getChildList(id, "directors", "director");
        List<String> actors = getChildList(id, "actors", "actor");

        writer.print("  {\n");
        writer.printf("    \"id\": %d,\n", id);
        writer.printf("    \"title\": %s,\n", escapeJson(title));
        writer.printf("    \"year\": %s,\n", escapeJson(year));
        writer.printf("    \"country\": %s,\n", escapeJson(country));
        writer.printf("    \"url\": %s,\n", escapeJson(url));
        writer.printf("    \"crawled_at\": %s,\n", escapeJson(crawledAt));
        writer.printf("    \"genres\": %s,\n", toJsonArray(genres));
        writer.printf("    \"directors\": %s,\n", toJsonArray(directors));
        writer.printf("    \"actors\": %s\n", toJsonArray(actors));
        writer.print("  }");
      }
      writer.println("\n]");
      System.out.println("[BACKUP] Exported UTF-8 JSON backup on disk to: " + file.getAbsolutePath());

    } catch (Exception e) {
      System.err.println("[BACKUP] Failed to export JSON: " + e.getMessage());
    }
  }

  private static String escapeJson(String s) {
    if (s == null)
      return "null";
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
  }

  private static String toJsonArray(List<String> list) {
    if (list == null || list.isEmpty())
      return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < list.size(); i++) {
      if (i > 0)
        sb.append(", ");
      sb.append(escapeJson(list.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  /** Closes the database connection. */
  public void close() {
    if (connection != null) {
      try {
        connection.close();
        System.out.println("[DB] Connection closed.");
      } catch (SQLException ignored) {
      }
    }
  }

  /** Returns the absolute path to the SQLite file (for display purposes). */
  public String getDbPath() {
    return new File(DB_FILE).getAbsolutePath();
  }

  public String getBackupJsonPath() {
    return new File(BACKUP_JSON_FILE).getAbsolutePath();
  }
}
