// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

/**
 * Автоматическая педаль соединяет последовательные аккорды одной рукой.
 * Новый аккорд начинается сразу, предыдущий отпускается после короткого перекрытия.
 * Часы передаются извне, поэтому переходы можно проверить без Android и ожиданий.
 */
internal class ChordPedal(enabled: Boolean = false, private val overlapMillis: Long = 150L) {
    private data class Contact(var note: Int?, var eligible: Boolean = true)
    private val contacts = mutableMapOf<Long, Contact>()
    private val held = linkedSetOf<Int>()
    private val transitioning = mutableMapOf<Int, Long>()
    var enabled: Boolean = enabled
        private set
    val heldNotes: Set<Int> get() = held + transitioning.keys
    val nextReleaseAtMillis: Long? get() = transitioning.values.minOrNull()

    init { require(overlapMillis >= 0L) }

    fun changeEnabled(value: Boolean) {
        enabled = value
        if (!value) clearHeld()
    }

    /** Все пальцы нового аккорда используют один переход, начиная с первого касания. */
    fun press(pointer: Long, note: Int, nowMillis: Long) {
        if (enabled && contacts.isEmpty()) {
            held.forEach { transitioning[it] = nowMillis + overlapMillis }
            held.clear()
        }
        // Общая нота должна сыграть заново, а не считаться уже зажатой педалью.
        held.remove(note)
        transitioning.remove(note)
        contacts[pointer] = Contact(note)
    }

    fun move(pointer: Long, note: Int?) {
        val contact = contacts[pointer] ?: return
        contact.note = note
        if (note != null) {
            held.remove(note)
            transitioning.remove(note)
        }
    }

    fun manualLatch(pointer: Long) { contacts[pointer]?.eligible = false }

    // Turning off gesture latching returns held fingers to normal pedal handling.
    fun restorePedalContacts() { contacts.values.forEach { it.eligible = true } }

    fun release(pointer: Long) {
        val contact = contacts.remove(pointer) ?: return
        if (enabled && contact.eligible) contact.note?.let(held::add)
    }

    /** Старые аккорды имеют собственные сроки: быстрая игра не откладывает их отпускание. */
    fun releaseDue(nowMillis: Long): Set<Int> {
        val due = transitioning.filterValues { it <= nowMillis }.keys.toSet()
        due.forEach { transitioning.remove(it) }
        return due
    }

    fun clearHeld() { held.clear(); transitioning.clear() }
    fun cancelGesture() { contacts.clear(); clearHeld() }
}
