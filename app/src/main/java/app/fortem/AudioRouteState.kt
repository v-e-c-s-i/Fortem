// SPDX-License-Identifier: GPL-3.0-only
package app.fortem

/**
 * Хранит последний устойчивый набор аудиовыходов отдельно от Android API,
 * чтобы одинаковые системные уведомления не перезапускали звук повторно.
 */
internal class AudioRouteState(initialDeviceIds: Set<Int>) {
    private var deviceIds = initialDeviceIds

    fun reset(newDeviceIds: Set<Int>) {
        deviceIds = newDeviceIds
    }

    fun update(newDeviceIds: Set<Int>): Boolean {
        if (newDeviceIds == deviceIds) return false
        deviceIds = newDeviceIds
        return true
    }
}
