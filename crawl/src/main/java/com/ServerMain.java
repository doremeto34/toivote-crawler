package com;
/* Chinh sua tren server github */

import com.controller.MovieController;
import com.db.DatabaseManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.net.httpserver.HttpServer;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Entry point cho Webservice (Bài 5: Tích hợp Caffeine Cache).
 *
 * Cấu hình Caffeine Cache:
 *   - expireAfterWrite: 20 giây (tự xóa sau 20s khi ghi mới)
 *   - expireAfterAccess: 10 giây (tự xóa sau 10s không có request đọc)
 *   - recordStats: Bật theo dõi hit rate / request count
 */
public class ServerMain {

  private static final int PORT = 8080;

  public static void main(String[] args) throws Exception {
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

    System.out.println("=".repeat(70));
    System.out.println("    TOIVOTE MOVIE WEBSERVICE (Bài 5: Caffeine Cache)");
    System.out.println("=".repeat(70));

    // Khởi tạo Caffeine Cache (TTL = 20s, TTI = 10s)
    Cache<String, String> cache = Caffeine.newBuilder()
        .expireAfterWrite(20, TimeUnit.SECONDS)
        .expireAfterAccess(10, TimeUnit.SECONDS)
        .recordStats()
        .build();

    // 1. Khởi tạo Database SQLite
    DatabaseManager db = new DatabaseManager();
    db.init();

    // 2. Tạo HttpServer
    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

    // 3. Đăng ký endpoint với MovieController
    server.createContext("/movie", new MovieController(db, cache));

    // 4. Khởi động server
    server.start();
    System.out.println("[SERVER] HttpServer dang chay tai: http://localhost:" + PORT);
    System.out.println("[SERVER] Endpoint: http://localhost:" + PORT + "/movie?url=<link_phim>");

    // Hook dọn dẹp khi tắt server
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\n[SERVER] Stop server...");
      server.stop(0);
      db.close();
    }));
  }
}
