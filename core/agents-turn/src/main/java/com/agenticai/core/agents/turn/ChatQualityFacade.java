package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The answer-quality and cost levers of a turn: the exact-match and semantic response caches and
 * the reasoning-depth helpers (self-consistency, Reflexion, task planning). Constructor-injected as
 * one unit; holds no logic.
 */
@Component
record ChatQualityFacade(
        LlmResponseCacheService responseCacheService,
        ObjectProvider<SemanticResponseCacheService> semanticResponseCacheService,
        AnswerConsistencyChecker answerConsistencyChecker,
        ReflexionService reflexionService,
        TaskPlanningService taskPlanningService) {}
