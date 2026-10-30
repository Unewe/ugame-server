package com.ugame.meta.player;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Игрок. Spring Data R2DBC сопоставляет поля с колонками в snake_case (passwordHash → password_hash).
 * Счётчики (кубки, победы) меняются атомарным UPDATE в PlayerRepository, а не через save().
 */
@Table("players")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "passwordHash")
public class Player {

    @Id
    private Long id;
    private String username;
    private String email;
    private String passwordHash;
    private int trophies;
    private int matchesPlayed;
    private int wins;
    private Instant createdAt;
}
