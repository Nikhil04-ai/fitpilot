package com.project.fitness.repository;

import com.project.fitness.model.ActivityType;
import com.project.fitness.model.PersonalBest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonalBestRepository extends JpaRepository<PersonalBest, String> {

    Optional<PersonalBest> findByUserIdAndActivityType(String userId, ActivityType activityType);

    List<PersonalBest> findByUserId(String userId);

    long countByUserId(String userId);
}
