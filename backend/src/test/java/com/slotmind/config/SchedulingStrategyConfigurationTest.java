package com.slotmind.config;

import com.slotmind.strategy.AISchedulingStrategy;
import com.slotmind.strategy.SchedulingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class SchedulingStrategyConfigurationTest {

    private final SchedulingStrategyConfiguration configuration = new SchedulingStrategyConfiguration();
    private final AISchedulingStrategy ai = mock(AISchedulingStrategy.class);

    @Test
    void alwaysSelectsAiAsThePrimaryRecommendationStrategy() {
        SchedulingStrategy selected = configuration.selectedSchedulingStrategy(ai);

        assertSame(ai, selected);
    }
}
