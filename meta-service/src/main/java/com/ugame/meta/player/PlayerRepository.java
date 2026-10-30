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
