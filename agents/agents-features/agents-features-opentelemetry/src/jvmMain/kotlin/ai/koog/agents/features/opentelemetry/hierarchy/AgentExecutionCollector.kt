package ai.koog.agents.features.opentelemetry.hierarchy

import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.agents.core.feature.handler.AgentLifecycleEventContext
import ai.koog.agents.features.opentelemetry.span.GenAIAgentSpan
import ai.koog.agents.features.opentelemetry.span.SpanProcessor
import io.github.oshai.kotlinlogging.KotlinLogging

internal class AgentExecutionCollector {

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private val eventIdToExecutionPathWithId: MutableMap<String, AgentExecutionInfoWithEventId> =
        mutableMapOf()

    internal fun addEventExecutionInfo(eventId: String, executionInfo: AgentExecutionInfo): AgentExecutionInfoWithEventId {
        val parent = eventIdToExecutionPathWithId[eventId]?.parent
        val executionInfoWithIdToAdd = AgentExecutionInfoWithEventId(
            parent = parent,
            eventId = eventId,
            executionInfo = executionInfo
        )
        eventIdToExecutionPathWithId[eventId] = executionInfoWithIdToAdd
        return executionInfoWithIdToAdd
    }

    internal fun getParentEventId(eventId: String): String? {
        return eventIdToExecutionPathWithId[eventId]?.parent?.eventId
    }

    // TODO: SD --
    internal fun SpanProcessor.findClosestParentSpan(eventContext: AgentLifecycleEventContext): GenAIAgentSpan? {
        var checkEvent: AgentExecutionInfo? = eventContext.executionInfo.parent
        var span: GenAIAgentSpan? = null

        while (checkEvent != null) {
            val checkSpan = this.getSpanCatching<GenAIAgentSpan>(checkEvent.id)
            if (checkSpan != null) {
                span = checkSpan
                break
            }

            checkEvent = checkEvent.parent
        }

        return span
    }

    // TODO: SD --
    internal fun AgentLifecycleEventContext.getParentEventIdLogging(): String? =
        this.executionInfo.parent?.path() ?: run {
            logger.error { "Undefined agent event parent for event with id: ${this.executionInfo.path()}" }
            null
        }
}
