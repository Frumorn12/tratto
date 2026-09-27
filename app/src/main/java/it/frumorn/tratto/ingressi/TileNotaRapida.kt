package it.frumorn.tratto.ingressi

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import it.frumorn.tratto.notarapida.NotaRapidaActivity

/**
 * Riquadro "Nota rapida" nelle impostazioni rapide: apre il foglietto sopra l'app che si sta
 * usando. Il doppio clic della penna fuori da Tratto non si puo' ascoltare, questo si'.
 */
class TileNotaRapida : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, NotaRapidaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (isLocked) unlockAndRun { startActivityAndCollapse(pi) } else startActivityAndCollapse(pi)
    }
}
