package app.uab.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Mantiene viva la app con la pantalla apagada o en segundo plano: servicio en primer plano
 * (notificación fija) + bloqueo de CPU y de wifi. Así el despertador puede seguir escribiéndole a las IA
 * mientras el usuario duerme o trabaja. Se detiene desde la notificación ("Detener") o al cerrar la app.
 */
public class KeepAliveService extends Service {
    static final String CH = "uab_keep", STOP = "app.uab.bridge.STOP";
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "UAB activa", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, KeepAliveService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        nb.setContentTitle("UAB Puente activo").setContentText("Las IA pueden seguir trabajando con la pantalla apagada")
          .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setContentIntent(open);
        if (Build.VERSION.SDK_INT >= 23) nb.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Detener", stop).build());
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
        try {
            if (cpu == null) { cpu = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uab:keep"); cpu.setReferenceCounted(false); }
            cpu.acquire(12 * 3600 * 1000L);   // se renueva cada vez que se abre la app
            if (wifi == null) { wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "uab:wifi"); wifi.setReferenceCounted(false); }
            if (!wifi.isHeld()) wifi.acquire();
        } catch (Throwable ignored) { }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { if (cpu != null && cpu.isHeld()) cpu.release(); } catch (Throwable ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
