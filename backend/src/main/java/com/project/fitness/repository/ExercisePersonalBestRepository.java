package com.project.fitness.repository;

import com.project.fitness.model.ExercisePersonalBest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExercisePersonalBestRepository extends JpaRepository<ExercisePersonalBest, String> {

    Optional<ExercisePersonalBest> findByUserIdAndNormalisedName(String userId, String normalisedName);

    List<ExercisePersonalBest> findByUserIdOrderByBestEstimatedOneRepMaxDesc(String userId);

    List<ExercisePersonalBest> findByUserId(String userId);
}
