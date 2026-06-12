package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.client.services.ClientServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Launch an API call on the services background scope and deliver the result on the
 * client render thread.
 *
 * When [busy] is provided, re-entry is guarded: the call is skipped entirely while a
 * previous call holds the flag, and the flag is released just before [onResult] runs
 * (on the main thread).
 *
 * Unexpected exceptions from [block] are folded into an [ApiError.Unexpected] failure
 * so the UI always receives exactly one callback.
 */
fun <T> ClientServices.call(
    busy: AtomicBoolean? = null,
    block: suspend () -> ApiResult<T>,
    onResult: (ApiResult<T>) -> Unit,
) {
    if (busy != null && !busy.compareAndSet(false, true)) return
    scope.launch {
        val result = try {
            block()
        } catch (e: CancellationException) {
            busy?.set(false)
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(ApiError.Unexpected(0, e.message ?: "Unexpected client error"))
        }
        onMainThread {
            busy?.set(false)
            onResult(result)
        }
    }
}

/** Tooltip shown on mutating buttons while the backend is unreachable. */
const val OFFLINE_TOOLTIP = "Unavailable while offline - can't reach schemat.io"

/**
 * Disable this button (with an explanatory tooltip) when the last `/mod/me` refresh
 * failed offline. Apply to mutating actions at build time - widgets are rebuilt on
 * every tab/screen (re)init, so the state tracks [ClientServices.isOffline] closely.
 */
fun <T : AbstractWidget> T.disabledWhenOffline(services: ClientServices): T {
    if (services.isOffline()) {
        active = false
        setTooltip(Tooltip.create(Component.literal(OFFLINE_TOOLTIP)))
    }
    return this
}
