package com.ugame.meta.player;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    @Query("select p from Player p where lower(p.username) = lower(:username)")
    Optional<Player> findByUsernameIgnoreCase(String username);

    @Query("select count(p) > 0 from Player p where lower(p.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(String username);

    @Query("select count(p) > 0 from Player p where lower(p.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(String email);
}
