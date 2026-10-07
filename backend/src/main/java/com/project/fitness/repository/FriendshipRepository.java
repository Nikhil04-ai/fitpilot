package com.project.fitness.repository;

import com.project.fitness.model.Friendship;
import com.project.fitness.model.FriendshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendshipRepository extends JpaRepository<Friendship, String> {

    /**
     * The friendship between two users, whichever of them asked.
     *
     * <p>One row per pair, so both orderings have to be checked — the alternative is
     * two rows per friendship that can disagree with each other.
     */
    @Query("""
           SELECT f FROM Friendship f
           WHERE (f.requester.id = :a AND f.addressee.id = :b)
              OR (f.requester.id = :b AND f.addressee.id = :a)
           """)
    Optional<Friendship> findBetween(@Param("a") String a, @Param("b") String b);

    /** Every friendship a user is on either side of, in a given state. */
    @Query("""
           SELECT f FROM Friendship f
           WHERE (f.requester.id = :userId OR f.addressee.id = :userId)
             AND f.status = :status
           ORDER BY f.createdAt DESC
           """)
    List<Friendship> findAllForUser(@Param("userId") String userId,
                                    @Param("status") FriendshipStatus status);

    /** Requests waiting on this user to answer — the invite badge. */
    @Query("SELECT f FROM Friendship f WHERE f.addressee.id = :userId AND f.status = 'PENDING' "
         + "ORDER BY f.createdAt DESC")
    List<Friendship> findPendingFor(@Param("userId") String userId);

    /** Requests this user sent that have not been answered. */
    @Query("SELECT f FROM Friendship f WHERE f.requester.id = :userId AND f.status = 'PENDING' "
         + "ORDER BY f.createdAt DESC")
    List<Friendship> findSentBy(@Param("userId") String userId);

    long countByAddresseeIdAndStatus(String addresseeId, FriendshipStatus status);
}
