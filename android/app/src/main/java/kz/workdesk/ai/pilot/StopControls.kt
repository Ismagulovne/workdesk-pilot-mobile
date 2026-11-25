package kz.workdesk.ai.pilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Кнопка «Стоп» в уведомлении. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PilotService.instance?.stopByUser("уведомление")
    }
}

/** Плитка «Стоп ИИ» в быстрых настройках. */
class StopTileService : TileService() {
    override fun onStartListening() {
        val active = PilotService.instance?.target != null
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (active) "ИИ работает" else "ИИ не управляет"
            updateTile()
        }
    }

    override fun onClick() {
        PilotService.instance?.stopByUser("плитка")
        onStartListening()
    }
}
