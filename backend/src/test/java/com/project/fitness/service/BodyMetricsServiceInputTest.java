package com.project.fitness.service;

import com.project.fitness.model.BodyMetricsLog;
import com.project.fitness.model.User;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What {@code updateMetrics} writes when it is given little or nothing (PLAN P4-12).
 *
 * <p>Every call wrote a weigh-in row, even with an empty body, and stored {@code 0} for
 * any value the profile didn't have. Zero kg is not a measurement, so it showed up in
 * the chart, the history and the export as a real one. Lenient stubs, unlike
 * BodyMetricsServiceTest, because the point of several tests is that nothing is saved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BodyMetricsService — sparse input")
class BodyMetricsServiceInputTest {

    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private GoalService goalService;
    @InjectMocks private BodyMetricsService bodyMetricsService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").build();     // a profile with no weight or height yet
        lenient().when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(bodyMetricsLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(goalService.refreshStateGoals(any())).thenReturn(List.of());
    }

    private BodyMetricsLog savedRow() {
        ArgumentCaptor<BodyMetricsLog> captor = ArgumentCaptor.forClass(BodyMetricsLog.class);
        verify(bodyMetricsLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an empty save is refused, and writes no weigh-in")
    void emptySaveIsRefused() {
        assertThatThrownBy(() -> bodyMetricsService.updateMetrics("user-1", null, null))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessageContaining("weight or a height");

        verify(bodyMetricsLogRepository, never()).save(any());
        verifyNoInteractions(goalService);
    }

    @Test
    @DisplayName("a dead session still gets its 409, not the empty-save 400")
    void deadSessionIsCheckedFirst() {
        lenient().when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bodyMetricsService.updateMetrics("ghost", null, null))
                .isInstanceOf(com.project.fitness.exceptions.AccountNotFoundException.class);
    }

    @Test
    @DisplayName("a weight with no height on file is stored with a null height, not 0")
    void unknownHeightIsNullNotZero() {
        bodyMetricsService.updateMetrics("user-1", 72.5, null);

        BodyMetricsLog row = savedRow();
        assertThat(row.getWeight()).isEqualTo(72.5);
        assertThat(row.getHeight()).isNull();
        assertThat(row.getBmi()).isNull();
        assertThat(row.getBmiCategory()).isNull();
    }

    @Test
    @DisplayName("a height with no weight on file is stored with a null weight, not 0")
    void unknownWeightIsNullNotZero() {
        bodyMetricsService.updateMetrics("user-1", null, 180.0);

        BodyMetricsLog row = savedRow();
        assertThat(row.getWeight()).isNull();
        assertThat(row.getHeight()).isEqualTo(180.0);
    }

    @Test
    @DisplayName("a partial update still fills the other value from the profile")
    void partialUpdateUsesTheProfileForTheRest() {
        user.setHeight(175.0);

        bodyMetricsService.updateMetrics("user-1", 70.0, null);

        BodyMetricsLog row = savedRow();
        assertThat(row.getHeight()).isEqualTo(175.0);
        assertThat(row.getBmi()).isEqualTo(22.9);
    }
}
