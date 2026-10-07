package com.project.fitness.service;

import com.project.fitness.model.Goal;
import com.project.fitness.model.GoalType;
import com.project.fitness.model.User;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.MealLogRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoalService Authorization Tests")
class GoalServiceAuthorizationTest {

    @Mock private GoalRepository goalRepository;
    @Mock private UserRepository userRepository;
    @Mock private MealLogRepository mealLogRepository;
    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @InjectMocks private GoalService goalService;

    private Goal othersGoal;

    @BeforeEach
    void setUp() {
        User owner = User.builder().id("owner-1").build();
        othersGoal = Goal.builder()
                .id("goal-1").user(owner).type(GoalType.WORKOUTS_PER_WEEK)
                .targetValue(5.0).currentValue(2.0).isCompleted(false).build();
    }

    @Test
    @DisplayName("updateGoal rejects edits to another user's goal")
    void updateGoal_deniesNonOwner() {
        when(goalRepository.findById("goal-1")).thenReturn(Optional.of(othersGoal));

        assertThatThrownBy(() -> goalService.updateGoal("attacker-9", "goal-1", 1.0, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(goalRepository, never()).save(any());
        assertThat(othersGoal.getTargetValue()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("deleteGoal rejects deleting another user's goal")
    void deleteGoal_deniesNonOwner() {
        when(goalRepository.findById("goal-1")).thenReturn(Optional.of(othersGoal));

        assertThatThrownBy(() -> goalService.deleteGoal("attacker-9", "goal-1"))
                .isInstanceOf(AccessDeniedException.class);

        verify(goalRepository, never()).delete(any());
        verify(goalRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("deleteGoal allows the owner")
    void deleteGoal_allowsOwner() {
        when(goalRepository.findById("goal-1")).thenReturn(Optional.of(othersGoal));

        goalService.deleteGoal("owner-1", "goal-1");

        verify(goalRepository).delete(othersGoal);
    }

    @Test
    @DisplayName("updateGoal allows the owner")
    void updateGoal_allowsOwner() {
        when(goalRepository.findById("goal-1")).thenReturn(Optional.of(othersGoal));
        when(goalRepository.save(any(Goal.class))).thenAnswer(i -> i.getArgument(0));

        Goal updated = goalService.updateGoal("owner-1", "goal-1", 10.0, null);

        assertThat(updated.getTargetValue()).isEqualTo(10.0);
    }
}
