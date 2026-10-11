package com.project.fitness.repository;

import com.project.fitness.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    User findByEmail(String email);

    /**
     * Just the token version, for the check made on every authenticated request. Not a
     * whole {@code User}: this runs once per call, and the entity drags a row's worth of
     * columns (and a persistence-context entry) for one integer.
     */
    @Query("select u.tokenVersion from User u where u.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") String id);

    /**
     * Adds one to the token version without loading the user, which retires every access
     * token issued so far. For logout, which has only an id; a password change or reset
     * already holds the entity and uses {@link User#retireIssuedTokens()}. Atomic, so two
     * racing calls both count.
     */
    @Modifying
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
    int retireIssuedTokens(@Param("id") String id);
}
