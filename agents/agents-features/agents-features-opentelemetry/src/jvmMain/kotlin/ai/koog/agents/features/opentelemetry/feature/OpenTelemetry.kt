package ai.koog.agents.features.opentelemetry.feature

import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.handler.AgentLifecycleEventContext
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.utils.SerializationUtils
import ai.koog.agents.features.opentelemetry.attribute.CommonAttributes
import ai.koog.agents.features.opentelemetry.attribute.CustomAttribute
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.event.AssistantMessageEvent
import ai.koog.agents.features.opentelemetry.event.ChoiceEvent
import ai.koog.agents.features.opentelemetry.event.ModerationResponseEvent
import ai.koog.agents.features.opentelemetry.event.SystemMessageEvent
import ai.koog.agents.features.opentelemetry.event.ToolMessageEvent
import ai.koog.agents.features.opentelemetry.event.UserMessageEvent
import ai.koog.agents.features.opentelemetry.span.CreateAgentSpan
import ai.koog.agents.features.opentelemetry.span.ExecuteToolSpan
import ai.koog.agents.features.opentelemetry.span.GenAIAgentSpan
import ai.koog.agents.features.opentelemetry.span.InferenceSpan
import ai.koog.agents.features.opentelemetry.span.InvokeAgentSpan
import ai.koog.agents.features.opentelemetry.span.NodeExecuteSpan
import ai.koog.agents.features.opentelemetry.span.SpanEndStatus
import ai.koog.agents.features.opentelemetry.span.SpanCollector
import ai.koog.agents.features.opentelemetry.span.StrategySpan
import ai.koog.agents.features.opentelemetry.span.SubgraphExecuteSpan
import ai.koog.agents.utils.HiddenString
import ai.koog.prompt.message.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.StatusCode
import kotlin.reflect.KType

/**
 * Represents the OpenTelemetry integration feature for tracking and managing spans and contexts
 * within the AI Agent framework. This class manages the lifecycle of spans for various operations,
 * including agent executions, node processing, LLM calls, and tool calls.
 */
public class OpenTelemetry {

    /**
     * Companion object implementing agent feature, handling [OpenTelemetry] creation and installation.
     */
    public companion object Feature : AIAgentGraphFeature<OpenTelemetryConfig, OpenTelemetry> {

        private val logger = KotlinLogging.logger { }

        override val key: AIAgentStorageKey<OpenTelemetry> = AIAgentStorageKey("agents-features-opentelemetry")

        override fun createInitialConfig(): OpenTelemetryConfig {
            return OpenTelemetryConfig()
        }

        override fun install(
            config: OpenTelemetryConfig,
            pipeline: AIAgentGraphPipeline,
        ): OpenTelemetry {
            val openTelemetry = OpenTelemetry()
            val tracer = config.tracer
            val spanCollector = SpanCollector(tracer = tracer, verbose = config.isVerbose)
            val spanAdapter = config.spanAdapter

            //region Agent

            pipeline.interceptAgentStarting(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry before agent started handler" }

                // Check if Create Agent Span is already added (when running the same agent >= 1 times)
                val createAgentSpanId = eventContext.eventId
                val createAgentSpan = spanCollector.getSpan(createAgentSpanId) ?: run {
                    val span = CreateAgentSpan(
                        parentSpan = null,
                        id = createAgentSpanId,
                        name = eventContext.context.agentId,
                        model = eventContext.agent.agentConfig.model,
                        agentId = eventContext.context.agentId
                    )

                    spanAdapter?.onBeforeSpanStarted(span)
                    spanCollector.startSpan(span)
                    span
                }

                // Create InvokeAgentSpan
                val invokeAgentSpan = InvokeAgentSpan(
                    parentSpan = createAgentSpan,
                    id = eventContext.eventId,
                    name = eventContext.agent.id,
                    provider = eventContext.agent.agentConfig.model.provider,
                    agentId = eventContext.agent.id,
                    runId = eventContext.runId,
                )

                spanAdapter?.onBeforeSpanStarted(invokeAgentSpan)
                spanCollector.startSpan(invokeAgentSpan)
            }

            pipeline.interceptAgentCompleted(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry agent finished handler" }

                // Find parent span - InvokeAgentSpan
                val invokeAgentSpan = spanCollector.getSpanCatching<InvokeAgentSpan>(eventContext.eventId)
                    ?: return@intercept

                spanAdapter?.onBeforeSpanFinished(invokeAgentSpan)
                spanCollector.endSpan(span = invokeAgentSpan)
            }

            pipeline.interceptAgentExecutionFailed(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry agent run error handler" }

                // Finish current InvokeAgentSpan
                val invokeAgentSpan = spanCollector.getSpanCatching<InvokeAgentSpan>(eventContext.eventId)
                    ?: return@intercept

                invokeAgentSpan.addAttribute(
                    attribute = SpanAttributes.Response.FinishReasons(
                        listOf(SpanAttributes.Response.FinishReasonType.Error)
                    )
                )

                spanAdapter?.onBeforeSpanFinished(invokeAgentSpan)
                spanCollector.endSpan(
                    span = invokeAgentSpan,
                    spanEndStatus = SpanEndStatus(code = StatusCode.ERROR, description = eventContext.throwable.message)
                )
            }

            pipeline.interceptAgentClosing(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry before agent closed handler" }

                // Stop all unfinished spans except the current agent create span
                spanCollector.endUnfinishedSpans { span -> span.id != eventContext.eventId }

                // Stop agent create span
                val agentSpan = spanCollector.getSpanCatching<CreateAgentSpan>(eventContext.eventId)
                    ?: return@intercept

                spanAdapter?.onBeforeSpanFinished(agentSpan)
                spanCollector.endSpan(span = agentSpan)

                // Just in case we miss some spans, stop them as well
                spanCollector.endUnfinishedSpans()
            }

            //endregion Agent

            //region Strategy

            pipeline.interceptStrategyStarting(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry before subgraph handler" }

                val parentEventId = spanCollector.getParentEventIdLogging()
                    ?: return@intercept

                // Get parent span (Invoke Agent Span)
                val parentSpan = spanCollector.getSpanCatching<InvokeAgentSpan>(parentEventId)
                    ?: return@intercept

                // Create a Strategy Span
                val strategySpan = StrategySpan(
                    id = eventContext.eventId,
                    name = eventContext.strategy.name,
                    parentSpan = parentSpan,
                    runId = eventContext.context.runId,
                    strategyName = eventContext.strategy.name,
                )

                spanAdapter?.onBeforeSpanStarted(strategySpan)
                spanCollector.startSpan(strategySpan)
            }

            pipeline.interceptStrategyCompleted(this) intercept@{ eventContext ->
                val eventId = eventContext.executionInfo.path()

                // Find current Strategy Span
                val strategySpan = spanCollector.getSpanCatching<StrategySpan>(eventId)
                    ?: return@intercept

                spanAdapter?.onBeforeSpanFinished(strategySpan)
                spanCollector.endSpan(span = strategySpan)
            }

            //endregion Strategy

            //region Node

            pipeline.interceptNodeExecutionStarting(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry node starting handler" }

                // Get parent span (node, subgraph, or agent strategy)
                val parentSpan = spanCollector.findClosestParentSpan(eventContext)
                    ?: return@intercept

                // Create Node Execute Span
                val nodeExecuteSpan = NodeExecuteSpan(
                    id = eventContext.executionInfo.path(),
                    name = eventContext.executionInfo.partName,
                    parentSpan = parentSpan,
                    runId = eventContext.context.runId,
                    nodeId = eventContext.node.id,
                    nodeInput = nodeDataToString(eventContext.input, eventContext.inputType),
                )

                spanAdapter?.onBeforeSpanStarted(nodeExecuteSpan)
                spanCollector.startSpan(nodeExecuteSpan)
            }

            pipeline.interceptNodeExecutionCompleted(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry node completed handler" }

                // Find existing span (Node Execute Span)
                val eventId = eventContext.executionInfo.path()
                val nodeExecuteSpan = spanCollector.getSpanCatching<NodeExecuteSpan>(eventId)
                    ?: return@intercept

                val attributesToAdd = buildList {
                    nodeDataToString(eventContext.output, eventContext.outputType)?.let { nodeOutput ->
                        add(CustomAttribute(key = "koog.node.output", value = HiddenString(nodeOutput)))
                    }
                }

                nodeExecuteSpan.addAttributes(attributesToAdd)

                spanAdapter?.onBeforeSpanFinished(nodeExecuteSpan)
                spanCollector.endSpan(nodeExecuteSpan)
            }

            pipeline.interceptNodeExecutionFailed(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry node execution failed handler" }

                // Find existing span (Node Execute Span)
                val eventId = eventContext.eventId
                val nodeExecuteSpan = spanCollector.getSpanCatching<NodeExecuteSpan>(eventId)
                    ?: return@intercept

                spanAdapter?.onBeforeSpanFinished(nodeExecuteSpan)
                spanCollector.endSpan(
                    span = nodeExecuteSpan,
                    spanEndStatus = SpanEndStatus(code = StatusCode.ERROR, description = eventContext.throwable.message)
                )
            }

            //endregion Node

            //region Subgraph

            pipeline.interceptSubgraphExecutionStarting(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry before subgraph handler" }

                // Get parent span (node, subgraph, or agent strategy)
                val parentSpan = spanCollector.findClosestParentSpan(eventContext) ?: return@intercept

                // Create SubgraphExecuteSpan
                val subgraphExecuteSpan = SubgraphExecuteSpan(
                    id = eventContext.executionInfo.path(),
                    name = eventContext.executionInfo.partName,
                    parentSpan = parentSpan,
                    runId = eventContext.context.runId,
                    subgraphInput = nodeDataToString(eventContext.input, eventContext.inputType),
                    subgraphId = eventContext.subgraph.id
                )

                spanAdapter?.onBeforeSpanStarted(subgraphExecuteSpan)
                spanCollector.startSpan(subgraphExecuteSpan)
            }

            pipeline.interceptSubgraphExecutionCompleted(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry after subgraph handler" }

                // Find the existing span (Subgraph Execute Span)
                val eventId = eventContext.executionInfo.path()
                val subgraphExecuteSpan = spanCollector.getSpanCatching<SubgraphExecuteSpan>(eventId)
                    ?: return@intercept

                val attributesToAdd = buildList {
                    nodeDataToString(eventContext.output, eventContext.outputType)?.let { nodeOutput ->
                        add(CustomAttribute(key = "koog.subgraph.output", value = HiddenString(nodeOutput)))
                    }
                }

                subgraphExecuteSpan.addAttributes(attributesToAdd)

                spanAdapter?.onBeforeSpanFinished(subgraphExecuteSpan)
                spanCollector.endSpan(subgraphExecuteSpan)
            }

            pipeline.interceptSubgraphExecutionFailed(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry subgraph execution error handler" }

                // Find the existing span (Subgraph Execute Span)
                val eventId = eventContext.executionInfo.path()
                val subgraphExecuteSpan = spanCollector.getSpanCatching<SubgraphExecuteSpan>(eventId)
                    ?: return@intercept

                spanAdapter?.onBeforeSpanFinished(subgraphExecuteSpan)
                spanCollector.endSpan(
                    span = subgraphExecuteSpan,
                    spanEndStatus = SpanEndStatus(code = StatusCode.ERROR, description = eventContext.throwable.message)
                )
            }

            //endregion Subgraph

            //region LLM Call

            pipeline.interceptLLMCallStarting(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry before LLM call handler" }

                // Get parent span (node or subgraph)
                val parentId = spanCollector.getParentEventIdLogging()
                    ?: return@intercept

                val parentSpan = spanCollector.getSpanCatching<NodeExecuteSpan>(parentId)
                    ?: return@intercept

                val provider = eventContext.model.provider
                val model = eventContext.model
                val temperature = eventContext.prompt.params.temperature ?: 0.0

                val inferenceSpan = InferenceSpan(
                    id = eventContext.executionInfo.path(),
                    name = eventContext.executionInfo.partName,
                    parentSpan = parentSpan,
                    provider = provider,
                    runId = eventContext.runId,
                    content = eventContext.prompt.messages.lastOrNull()?.content ?: "",
                    model = model,
                    temperature = temperature,
                    maxTokens = eventContext.prompt.params.maxTokens,
                )

                // Add events to the InferenceSpan after the span is created
                val eventsFromMessages = eventContext.prompt.messages.map { message ->
                    when (message) {
                        is Message.System -> {
                            SystemMessageEvent(provider, message)
                        }

                        is Message.User -> {
                            UserMessageEvent(provider, message)
                        }

                        is Message.Assistant, is Message.Reasoning -> {
                            AssistantMessageEvent(provider, message)
                        }

                        is Message.Tool.Call -> {
                            ChoiceEvent(provider, message, arguments = message.contentJson)
                        }

                        is Message.Tool.Result -> {
                            ToolMessageEvent(
                                provider = provider,
                                toolCallId = message.id,
                                content = message.content
                            )
                        }
                    }
                }

                inferenceSpan.addEvents(eventsFromMessages)

                // Start span
                spanAdapter?.onBeforeSpanStarted(inferenceSpan)
                spanCollector.startSpan(inferenceSpan)
            }

            pipeline.interceptLLMCallCompleted(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry after LLM call handler" }

                // Find the current span (Inference Span)
                val inferenceSpanId = eventContext.executionInfo.path()
                val inferenceSpan = spanCollector.getSpanCatching<InferenceSpan>(inferenceSpanId)
                    ?: return@intercept

                val provider = eventContext.model.provider

                // Add attributes to the InferenceSpan before finishing the span
                val attributesToAdd = buildList {
                    eventContext.responses.lastOrNull()?.let { message ->
                        message.metaInfo.inputTokensCount?.let { inputTokensCount ->
                            add(SpanAttributes.Usage.InputTokens(inputTokensCount))
                        }
                        message.metaInfo.outputTokensCount?.let { outputTokensCount ->
                            add(SpanAttributes.Usage.OutputTokens(outputTokensCount))
                        }
                        message.metaInfo.totalTokensCount?.let { totalTokensCount ->
                            add(SpanAttributes.Usage.TotalTokens(totalTokensCount))
                        }
                    }
                }

                inferenceSpan.addAttributes(attributesToAdd)

                // Add events to the InferenceSpan before finishing the span
                val eventsToAdd = buildList {
                    eventContext.responses.mapIndexed { index, message ->
                        when (message) {
                            is Message.Assistant, is Message.Reasoning -> {
                                add(AssistantMessageEvent(provider, message))
                            }

                            is Message.Tool.Call -> {
                                add(ChoiceEvent(provider, message, arguments = message.contentJson, index = index))
                            }
                        }
                    }

                    eventContext.moderationResponse?.let { response ->
                        add(ModerationResponseEvent(provider, response))
                    }
                }

                inferenceSpan.addEvents(eventsToAdd)

                // Add attributes to InferenceSpan

                // Finish Reasons Attribute
                eventContext.responses.lastOrNull()?.let { message ->
                    val finishReasonsAttribute = when (message) {
                        is Message.Assistant, is Message.Reasoning -> {
                            SpanAttributes.Response.FinishReasons(reasons = listOf(SpanAttributes.Response.FinishReasonType.Stop))
                        }

                        is Message.Tool.Call -> {
                            SpanAttributes.Response.FinishReasons(reasons = listOf(SpanAttributes.Response.FinishReasonType.ToolCalls))
                        }
                    }

                    inferenceSpan.addAttribute(finishReasonsAttribute)
                }

                // Stop InferenceSpan
                spanAdapter?.onBeforeSpanFinished(inferenceSpan)
                spanCollector.endSpan(inferenceSpan)
            }

            //endregion LLM Call

            //region Tool Call

            pipeline.interceptToolCallStarting(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry tool call handler" }

                // Get parent span (node or subgraph)
                val parentId = spanCollector.getParentEventIdLogging()
                    ?: return@intercept

                val parentSpan = spanCollector.getSpanCatching<NodeExecuteSpan>(parentId)
                    ?: return@intercept

                val executeToolSpan = ExecuteToolSpan(
                    id = eventContext.executionInfo.path(),
                    name = eventContext.executionInfo.partName,
                    parentSpan = parentSpan,
                    toolName = eventContext.toolName,
                    toolArgs = eventContext.toolArgs.toString(),
                    toolDescription = null,
                    toolCallId = eventContext.toolCallId,
                )

                spanAdapter?.onBeforeSpanStarted(executeToolSpan)
                spanCollector.startSpan(executeToolSpan)
            }

            pipeline.interceptToolCallCompleted(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry tool result handler" }

                // Get the current ExecuteToolSpan
                val executeToolSpanId = eventContext.executionInfo.path()
                val executeToolSpan = spanCollector.getSpanCatching<ExecuteToolSpan>(executeToolSpanId)
                    ?: return@intercept

                eventContext.toolDescription?.let { toolDescription ->
                    executeToolSpan.addAttribute(
                        // gen_ai.tool.description
                        attribute = SpanAttributes.Tool.Description(description = toolDescription)
                    )
                }

                // End the ExecuteToolSpan span
                eventContext.toolResult?.let { result ->
                    executeToolSpan.addAttribute(
                        // gen_ai.tool.output.value
                        attribute = SpanAttributes.Tool.OutputValue(result.toString())
                    )
                }

                eventContext.toolDescription?.let { description ->
                    executeToolSpan.addAttribute(
                        attribute = SpanAttributes.Tool.Description(description)
                    )
                }

                spanAdapter?.onBeforeSpanFinished(span = executeToolSpan)
                spanCollector.endSpan(span = executeToolSpan)
            }

            pipeline.interceptToolCallFailed(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry tool call failure handler" }

                // Get the current ExecuteToolSpan using executionInfo.path()
                val executeToolSpanId = eventContext.executionInfo.path()
                val executeToolSpan = spanCollector.getSpanCatching<ExecuteToolSpan>(executeToolSpanId)
                    ?: return@intercept

                eventContext.toolDescription?.let { toolDescription ->
                    executeToolSpan.addAttribute(
                        // gen_ai.tool.description
                        attribute = SpanAttributes.Tool.Description(description = toolDescription)
                    )
                }

                executeToolSpan.addAttribute(
                    attribute = CommonAttributes.Error.Type(eventContext.message)
                )

                eventContext.toolDescription?.let { description ->
                    executeToolSpan.addAttribute(
                        attribute = SpanAttributes.Tool.Description(description)
                    )
                }

                // End the ExecuteToolSpan span
                spanAdapter?.onBeforeSpanFinished(executeToolSpan)
                spanCollector.endSpan(
                    span = executeToolSpan,
                    spanEndStatus = SpanEndStatus(
                        code = StatusCode.ERROR,
                        description = eventContext.error?.message
                    )
                )
            }

            pipeline.interceptToolValidationFailed(this) intercept@{ eventContext ->
                logger.debug { "Execute OpenTelemetry tool validation error handler" }

                // Get the current ExecuteToolSpan using executionInfo.path()
                val executeToolSpanId = eventContext.executionInfo.path()
                val executeToolSpan = spanCollector.getSpanCatching<ExecuteToolSpan>(executeToolSpanId)
                    ?: return@intercept

                eventContext.toolDescription?.let { toolDescription ->
                    executeToolSpan.addAttribute(
                        // gen_ai.tool.description
                        attribute = SpanAttributes.Tool.Description(description = toolDescription)
                    )
                }

                executeToolSpan.addAttribute(
                    attribute = CommonAttributes.Error.Type(eventContext.message)
                )

                eventContext.toolDescription?.let { description ->
                    executeToolSpan.addAttribute(
                        attribute = SpanAttributes.Tool.Description(description)
                    )
                }

                // End the ExecuteToolSpan span
                spanAdapter?.onBeforeSpanFinished(executeToolSpan)
                spanCollector.endSpan(
                    span = executeToolSpan,
                    spanEndStatus = SpanEndStatus(code = StatusCode.ERROR, description = eventContext.message)
                )
            }

            //endregion Tool Call

            return openTelemetry
        }

        //region Private Methods

        /**
         * Retrieves the [String] representation of the given data based on its type.
         */
        private fun nodeDataToString(data: Any?, dataType: KType): String? {
            data ?: return null

            @OptIn(InternalAgentsApi::class)
            return SerializationUtils.encodeDataToStringOrDefault(data, dataType)
        }

        /**
         * Finds the closest parent span by traversing up the execution hierarchy.
         * Returns null if no parent span is found.
         */
        private fun SpanCollector.findClosestParentSpan(eventContext: AgentLifecycleEventContext): GenAIAgentSpan? {
            var checkEvent: AgentExecutionInfo? = eventContext.executionInfo.parent
            var span: GenAIAgentSpan? = null

            while (checkEvent != null) {
                val checkSpan = this.getSpanCatching<GenAIAgentSpan>(checkEvent.path())
                if (checkSpan != null) {
                    span = checkSpan
                    break
                }

                checkEvent = checkEvent.parent
            }

            if (span == null) {
                logger.error { "Unable to find closest parent span for event with id: ${eventContext.executionInfo.path()}" }
            }

            return span
        }

        /**
         * Gets the parent event ID from the last active span, logging an error if not found.
         */
        private fun SpanCollector.getParentEventIdLogging(): String? {
            val lastActiveSpan = this.getLastActiveSpan()
            if (lastActiveSpan == null) {
                logger.error { "No active spans found to use as parent" }
                return null
            }
            return lastActiveSpan.id
        }

        //endregion Private Methods
    }
}
