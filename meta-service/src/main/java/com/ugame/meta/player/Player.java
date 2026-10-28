package com.ugame.meta.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String username;

    @Column(length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private int trophies;

    @Column(name = "matches_played", nullable = false)
    private int matchesPlayed;

    @Column(nullable = false)
    private int wins;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Player() {} // для JPA

    public Player(String username, String email, String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    /** Итог матча: победа +8 кубков, поражение −5 (не ниже нуля), ничья 0. */
    public void applyMatchResult(boolean won, boolean draw) {
        matchesPlayed++;
        if (draw) {
            return;
        }
        if (won) {
            wins++;
            trophies += 8;
        } else {
            trophies = Math.max(0, trophies - 5);
        }
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getTrophies() {
        return trophies;
    }

    public int getMatchesPlayed() {
        return matchesPlayed;
    }

    public int getWins() {
        return wins;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
