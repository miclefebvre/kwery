/*
 * Copyright (c) 2015 Andrew O'Malley
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.andrewoma.kwery.mapper.listener

import com.github.andrewoma.kwery.core.Session
import com.github.andrewoma.kwery.core.Transaction
import com.github.andrewoma.kwery.mapper.Table
import java.util.concurrent.ConcurrentHashMap

interface Listener {
    fun onEvent(session: Session, event: Event)
}

interface Event {
    val table: Table<*, *>
    val id: Any
}

interface TransformingEvent : Event {
    val new: Any
    var transformed: Any
}

data class PreInsertEvent(
        override val table: Table<*, *>,
        override val id: Any,
        override val new: Any,
        override var transformed: Any = new
) : TransformingEvent

data class PreUpdateEvent(
        override val table: Table<*, *>,
        override val id: Any,
        override val new: Any,
        val old: Any?,
        override var transformed: Any = new
) : TransformingEvent

data class InsertEvent(override val table: Table<*, *>, override val id: Any, val value: Any) : Event
data class DeleteEvent(override val table: Table<*, *>, override val id: Any, val value: Any?) : Event
data class UpdateEvent(override val table: Table<*, *>, override val id: Any, val new: Any, val old: Any?) : Event

abstract class DeferredListener(val postCommit: Boolean = true) : Listener {
    private val eventsByTransaction = ConcurrentHashMap<Long, MutableList<Event>>()

    override fun onEvent(session: Session, event: Event) {
        val transaction = session.currentTransaction
        if (transaction == null) {
            // If there is no transaction, assume auto commit is true
            onCommit(true, listOf(event))
        } else {
            if (!eventsByTransaction.containsKey(transaction.id)) {
                addCommitHook(transaction)
            }

            eventsByTransaction[transaction.id]?.add(event)
        }
    }

    private fun addCommitHook(transaction: Transaction) {
        eventsByTransaction[transaction.id] = arrayListOf<Event>()

        val onComplete: (Boolean, Session) -> Unit = { committed, _ ->
            val events = eventsByTransaction[transaction.id]!!
            eventsByTransaction.remove(transaction.id)
            onCommit(committed, events)
        }

        if (postCommit) {
            transaction.postCommitHandler(onComplete)
        } else {
            transaction.preCommitHandler { session -> onComplete(true, session) }
        }
    }

    abstract fun onCommit(committed: Boolean, events: List<Event>)
}
