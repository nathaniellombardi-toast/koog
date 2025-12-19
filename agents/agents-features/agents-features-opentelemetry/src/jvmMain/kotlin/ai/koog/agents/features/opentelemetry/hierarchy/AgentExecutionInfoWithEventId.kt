package ai.koog.agents.features.opentelemetry.hierarchy

import ai.koog.agents.core.agent.execution.AgentExecutionInfo

internal data class AgentExecutionInfoWithEventId(
    val parent: AgentExecutionInfoWithEventId?,
    val eventId: String,
    val executionInfo: AgentExecutionInfo
)
