package com;

import com.crawler.MovieCrawler;
import com.crawler.MovieDiscovery;
import com.db.DatabaseManager;
import com.model.Movie;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Entry point for Bài 2.
 *
 * Chức năng:
 *   - Tự động discover ~100 movie URLs từ toivote.com
 *   - Crawl dữ liệu và lưu vào SQLite (data/movies.db)
 *   - Tự động sao lưu file JSON (data/movies_backup.json)
 *   - Kết thúc tiến trình để shell script (run_crawler.sh) chạy lặp mỗi 5 giây.
 */
public class Main {

    private static final long CRAWL_DELAY_MS = 1200;
    private static final int DEFAULT_TARGET_COUNT = 100;

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        System.out.println("TOIVOTE.COM MOVIE CRAWLER");

        DatabaseManager db = new DatabaseManager();
        db.init();

        int targetCount = DEFAULT_TARGET_COUNT;
        String requestedGenre = null;

        for (int i = 0; i < args.length; i++) {
            if ("--genre".equals(args[i]) && i + 1 < args.length) {
                requestedGenre = args[++i];
            } else if ("--count".equals(args[i]) && i + 1 < args.length) {
                try {
                    targetCount = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignored) {}
            }
        }

        MovieDiscovery discovery = new MovieDiscovery();
        List<String> urls;
        if (requestedGenre != null && !requestedGenre.isBlank()) {
            System.out.printf("[MODE] Crawling by category: /genre/%s (target: %d)%n", requestedGenre, targetCount);
            urls = discovery.discoverByGenre(requestedGenre, targetCount);
        } else {
            System.out.printf("[MODE] Dynamic discovery across /discover/genres (target: ~%d URLs)%n", targetCount);
            urls = discovery.discoverMovies(targetCount);
        }

        if (urls == null || urls.isEmpty()) {
            System.err.println("[ERROR] No movie URLs discovered. Exiting.");
            db.close();
            return;
        }

        System.out.printf("%nTotal movie URLs ready to crawl: %d%n%n", urls.size());

        MovieCrawler crawler = new MovieCrawler();
        int success = 0;
        int failed = 0;

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            System.out.printf("[%3d/%3d] Crawling: %s%n", i + 1, urls.size(), url);

            Movie movie = crawler.crawl(url);

            if (movie != null) {
                try {
                    db.saveMovie(movie);
                    success++;
                } catch (Exception e) {
                    System.err.println("[MAIN] DB error: " + e.getMessage());
                    failed++;
                }
            } else {
                failed++;
            }

            // Polite delay
            if (i < urls.size() - 1) {
                Thread.sleep(CRAWL_DELAY_MS);
            }
        }

        System.out.println();
        System.out.printf("Crawl Summary: Success = %d | Failed = %d | Total in DB = %d%n",success, failed, db.getTotalMoviesCount());
        db.exportToJsonBackup();
        db.close();
        System.out.println("[MAIN] Process completed successfully.");
    }
}
