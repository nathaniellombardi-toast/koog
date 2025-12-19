package ai.koog.agents.features.opentelemetry.span

import ai.koog.agents.features.opentelemetry.event.GenAIAgentEvent
import ai.koog.agents.features.opentelemetry.extension.setAttributes
import ai.koog.agents.features.opentelemetry.extension.setEvents
import ai.koog.agents.features.opentelemetry.extension.setSpanStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class SpanCollector(
    private val tracer: Tracer,
    private val verbose: Boolean = false
) {

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    internal data class SpanNode(
        val span: GenAIAgentSpan,
        val children: MutableList<SpanNode> = mutableListOf()
    )

    private val rootSpans = mutableListOf<SpanNode>()
    private val spanIndex = mutableMapOf<String, SpanNode>()

    private val spansLock = ReentrantReadWriteLock()

    val spansCount: Int
        get() = spanIndex.count()

    fun addEventsToSpan(spanId: String, events: List<GenAIAgentEvent>) {
        spansLock.read {
            val spanNode = spanIndex[spanId] ?: error("Span with id '$spanId' not found")
            spanNode.span.addEvents(events)
        }
    }

    fun startSpan(
        span: GenAIAgentSpan,
        instant: Instant? = null,
    ) {
        logger.debug { "Starting span (name: ${span.name}, id: ${span.id})" }

        if (spanIndex.containsKey(span.id)) {
            logger.warn { "Span with id '${span.id}' already started" }
            return
        }

        val spanKind = span.kind
        val parentContext = span.parentSpan?.context ?: Context.current()

        val spanBuilder = tracer.spanBuilder(span.name)
            .setStartTimestamp(instant ?: Instant.now())
            .setSpanKind(spanKind)
            .setParent(parentContext)

        spanBuilder.setAttributes(span.attributes, verbose)

        val startedSpan = spanBuilder.startSpan()

        // Store newly started span
        addSpan(span)

        // Update span context and span properties
        span.span = startedSpan
        span.context = startedSpan.storeInContext(parentContext)

        logger.debug { "Span has been started (name: ${span.name}, id: ${span.id})" }
    }

    fun endSpan(
        span: GenAIAgentSpan,
        spanEndStatus: SpanEndStatus? = null
    ) {
        logger.debug { "Finishing the span (id: ${span.id})" }

        val spanToFinish = span.span

        spanToFinish.setAttributes(span.attributes, verbose)
        spanToFinish.setEvents(span.events, verbose)
        spanToFinish.setSpanStatus(spanEndStatus)
        spanToFinish.end()

        spansLock.write {
            val removedNode = spanIndex.remove(span.id)
            if (removedNode == null) {
                logger.warn {
                    "Span with id '${span.id}' not found. Make sure you do not delete span with same id several times"
                }
                return@write
            }

            // Remove from parent's children or from rootSpans
            val parentSpan = span.parentSpan
            if (parentSpan != null) {
                val parentNode = spanIndex[parentSpan.id]
                parentNode?.children?.remove(removedNode)
            } else {
                rootSpans.remove(removedNode)
            }
        }
    }

    inline fun <reified T : GenAIAgentSpan> getSpan(spanId: String): T? {
        return spanIndex[spanId]?.span as? T
    }

    inline fun <reified T : GenAIAgentSpan> getSpanOrThrow(spanId: String): T {
        val span = spanIndex[spanId]?.span ?: error("Span with id: $spanId not found")
        return span as? T
            ?: error(
                "Span with id <$spanId> is not of expected type. Expected: <${T::class.simpleName}>, actual: <${span::class.simpleName}>"
            )
    }

    inline fun <reified T : GenAIAgentSpan> getSpanCatching(spanId: String): T? {
        val getSpanResult = runCatching { getSpanOrThrow<T>(spanId) }
        if (getSpanResult.isSuccess) {
            return getSpanResult.getOrNull()
        }

        val throwable = getSpanResult.exceptionOrNull()
        logger.error(throwable) { "Unable to get a span with id: $spanId. Error: ${throwable?.message}" }
        return null
    }

    fun endUnfinishedSpans(filter: (GenAIAgentSpan) -> Boolean = { true }) {
        spanIndex.values
            .map { it.span }
            .filter { span ->
                val isRequireFinish = filter(span)
                isRequireFinish
            }
            .forEach { span ->
                logger.warn { "Force close span with id: ${span.id}" }
                endSpan(
                    span = span,
                    spanEndStatus = SpanEndStatus(StatusCode.UNSET)
                )
            }
    }

    //region Private Methods

    private fun addSpan(span: GenAIAgentSpan) {
        spansLock.write {
            val spanId = span.id
            val existingNode = spanIndex[spanId]

            check(existingNode == null) { "Span with id '$spanId' already added" }

            val newNode = SpanNode(span)
            spanIndex[span.id] = newNode

            // Add to parent's children or to rootSpans
            val parentSpan = span.parentSpan
            if (parentSpan != null) {
                val parentNode = spanIndex[parentSpan.id]
                    ?: error("Parent span with id '${parentSpan.id}' not found. Parent must be added before child.")
                parentNode.children.add(newNode)
            } else {
                rootSpans.add(newNode)
            }
        }
    }

    //endregion Private Methods
}
