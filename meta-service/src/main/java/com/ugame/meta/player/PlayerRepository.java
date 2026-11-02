package com.ugame.meta.player;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PlayerRepository extends ReactiveCrudRepository<Player, Long> {

    @Query("select * from players where lower(username) = lower(:username)")
    Mono<Player> findByUsernameIgnoreCase(String username);

    @Query("select exists(select 1 from players where lower(username) = lower(:username))")
    Mono<Boolean> existsByUsernameIgnoreCase(String username);

    @Query("select exists(select 1 from players where lower(email) = lower(:email))")
    Mono<Boolean> existsByEmailIgnoreCase(String email);

    /**
     * Гость становится обычным игроком: своё имя, email и пароль. Одним UPDATE, чтобы не затереть
     * кубки, которые параллельно мог начислить результат матча.
     *
     * @return 1 — обновлено, 0 — игрок не найден или уже не гость
     */
    @Modifying
    @Query("""
            update players
               set username = :username,
                   email = :email,
                   password_hash = :passwordHash,
                   guest = false
             where id = :id and guest
            """)
    Mono<Long> upgradeGuest(long id, String username, String email, String passwordHash);

    /**
     * Итог матча одним атомарным UPDATE — без чтения строки и без гонок между матчами.
     *
     * @param win          1 — победа, 0 — нет
     * @param trophiesDelta изменение кубков; итог не опускается ниже нуля
     */
    @Modifying
    @Query("""
            update players
               set matches_played = matches_played + 1,
                   wins = wins + :win,
                   trophies = greatest(0, trophies + :trophiesDelta)
             where id = :id
            """)
    Mono<Long> applyMatchResult(long id, int win, int trophiesDelta);
}
