package com.xnotes.core.platform

/**
 * The few runtime facilities common code needs that the Kotlin standard library does not offer on
 * every target. On the JVM each is the exact JDK facility the code used before (so Android and the
 * Swing host behave as they did); Kotlin/Native gets an equivalent.
 */

/** A task to run later on the UI thread. On the JVM this is `java.lang.Runnable` itself. */
expect fun interface Runnable {
    fun run()
}

/** A monotonic clock in nanoseconds with an arbitrary origin, for measuring intervals (`System.nanoTime` on the JVM). */
expect fun monotonicNanos(): Long

/** A monitor for [withLock]. On the JVM any object is one, as with `synchronized`. */
expect class Lock()

/** Run [block] holding [lock] (reentrant). `synchronized` on the JVM. */
expect inline fun <T> withLock(lock: Lock, block: () -> T): T

/** A map comparing keys by reference, not `equals` (`java.util.IdentityHashMap` on the JVM). */
expect fun <K, V> identityMap(): MutableMap<K, V>

/** [identityMap] sized for about [expectedMaxSize] entries. */
expect fun <K, V> identityMap(expectedMaxSize: Int): MutableMap<K, V>

/** A set comparing elements by reference (`Collections.newSetFromMap(IdentityHashMap())` on the JVM). */
fun <T> identitySet(): MutableSet<T> = IdentitySet()

private class IdentitySet<T> : AbstractMutableSet<T>() {
    private val map = identityMap<T, Boolean>()
    override val size: Int get() = map.size
    override fun add(element: T): Boolean = map.put(element, true) == null
    override fun remove(element: T): Boolean = map.remove(element) != null
    override fun contains(element: T): Boolean = map.containsKey(element)
    override fun clear() = map.clear()
    override fun iterator(): MutableIterator<T> = map.keys.iterator()
}

/**
 * [value] with [decimals] fraction digits for display, like `"%.Nf".format(value)`. The JVM keeps
 * that exact call, including its use of the default locale's decimal separator.
 */
expect fun formatDecimal(value: Double, decimals: Int): String

/** A thread-safe counter handing out 1, 2, 3, … (`AtomicLong.incrementAndGet` on the JVM). */
expect class IdCounter() {
    fun next(): Long
}
