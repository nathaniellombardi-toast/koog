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

    internal data class SpanNode(
        val span: GenAIAgentSpan,
        val children: MutableList<SpanNode> = mutableListOf()
    )

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    /**
     * A mutable map for tracking the hierarchical structure of spans within the system.
     *
     * Keys are span IDs, and values are [SpanNode] objects, which represent
     * individual spans and their associated child spans in a tree-like format.
     *
     * This map is used internally to manage and maintain relationships between spans,
     * enabling the system to properly construct and end spans during the tracing process.
     */
    private val spanIndex = mutableMapOf<String, SpanNode>()

    /**
     * A list tracking currently active (open) spans in chronological order.
     * When a span is started, it's added to the end of the list.
     * When a span is ended, it's removed from the list.
     * This allows finding the most recently opened span that's still active.
     */
    private val activeSpans = mutableListOf<GenAIAgentSpan>()

    /**
     * A read-write lock to ensure thread-safe access to the span index and active spans list.
     */
    private val spansLock = ReentrantReadWriteLock()

    val spansCount: Int
        get() = spansLock.read { spanIndex.size }

    /**
     * Returns the most recently opened span that is still active (not yet ended).
     * This represents the current "active" span in the execution context.
     *
     * @return The last active span, or null if no spans are currently active.
     */
    fun getLastActiveSpan(): GenAIAgentSpan? {
        return spansLock.read { activeSpans.lastOrNull() }
    }

    /**
     * Returns the most recently opened span of a specific type that is still active.
     *
     * @return The last active span of type T, or null if no matching span is active.
     */
    inline fun <reified T : GenAIAgentSpan> getLastActiveSpan(): T? {
        return spansLock.read { activeSpans.lastOrNull { it is T } as? T }
    }

    /**
     * Returns all currently active spans in chronological order (oldest first).
     *
     * @return A list of all active spans.
     */
    fun getActiveSpans(): List<GenAIAgentSpan> {
        return spansLock.read { activeSpans.toList() }
    }

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

        val spanKind = span.kind
        val parentContext = span.parentSpan?.context ?: Context.current()

        val spanBuilder = tracer.spanBuilder(span.name)
            .setStartTimestamp(instant ?: Instant.now())
            .setSpanKind(spanKind)
            .setParent(parentContext)

        spanBuilder.setAttributes(span.attributes, verbose)

        val startedSpan = spanBuilder.startSpan()

        // Store newly started span (with thread-safe check inside)
        val wasAdded = addSpan(span)
        if (!wasAdded) {
            logger.warn { "Span with id '${span.id}' already started" }
            startedSpan.end()
            return
        }

        // Update span context and span properties
        span.span = startedSpan
        span.context = startedSpan.storeInContext(parentContext)

        // Add to active spans list
        spansLock.write {
            activeSpans.add(span)
        }

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

            // Remove from parent's children
            val parentSpan = span.parentSpan
            if (parentSpan != null) {
                val parentNode = spanIndex[parentSpan.id]
                parentNode?.children?.remove(removedNode)
            }

            // Remove from active spans list
            activeSpans.remove(span)
        }
    }

    inline fun <reified T : GenAIAgentSpan> getSpan(spanId: String): T? {
        return spansLock.read { spanIndex[spanId]?.span as? T }
    }

    inline fun <reified T : GenAIAgentSpan> getSpanOrThrow(spanId: String): T {
        val span = spansLock.read { spanIndex[spanId]?.span } ?: error("Span with id: $spanId not found")
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
        // Take snapshot to avoid ConcurrentModificationException
        val spansToEnd = spansLock.read {
            spanIndex.values
                .map { it.span }
                .filter(filter)
                .toList()
        }

        spansToEnd.forEach { span ->
            logger.warn { "Force close span with id: ${span.id}" }
            endSpan(
                span = span,
                spanEndStatus = SpanEndStatus(StatusCode.UNSET)
            )
        }
    }

    //region Private Methods

    /**
     * Adds a span to the index.
     *
     * @return true if successfully added, false if it already exists.
     */
    private fun addSpan(span: GenAIAgentSpan): Boolean = spansLock.write {
        // Check if already exists
        if (spanIndex.containsKey(span.id)) {
            return@write false
        }

        val newNode = SpanNode(span)
        spanIndex[span.id] = newNode

        // Add to children
        val parentSpan = span.parentSpan
        if (parentSpan != null) {
            val parentNode = spanIndex[parentSpan.id]
                ?: error("Parent span with id '${parentSpan.id}' not found. Parent must be added before child.")

            parentNode.children.add(newNode)
        }

        // Root span (agent) has no parent.
        // It doesn't need to be added to any parent's children list.
        true
    }

    //endregion Private Methods
}
