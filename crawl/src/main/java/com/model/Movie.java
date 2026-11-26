package com.model;

import java.util.List;

/**
 * Data model representing a movie crawled from toivote.com
 */
public class Movie {
    private String url;
    private String title;
    private String year;
    private String country;
    private List<String> genres;
    private List<String> directors;
    private List<String> actors;

    public Movie() {}

    public Movie(String url, String title, String year, String country,
                 List<String> genres, List<String> directors, List<String> actors) {
        this.url = url;
        this.title = title;
        this.year = year;
        this.country = country;
        this.genres = genres;
        this.directors = directors;
        this.actors = actors;
    }

    // Getters & Setters
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getYear() { return year; }
    public void setYear(String year) { this.year = year; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }

    public List<String> getDirectors() { return directors; }
    public void setDirectors(List<String> directors) { this.directors = directors; }

    public List<String> getActors() { return actors; }
    public void setActors(List<String> actors) { this.actors = actors; }

    @Override
    public String toString() {
        return "Movie{" +
               "title='" + title + '\'' +
               ", year='" + year + '\'' +
               ", country='" + country + '\'' +
               ", genres=" + genres +
               ", directors=" + directors +
               ", actors=" + actors +
               '}';
    }
}
