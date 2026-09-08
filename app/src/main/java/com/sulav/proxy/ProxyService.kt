package com.sulav.proxy

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class ProxyService : Service() {
    companion object {
        const val ACTION_START="START"; const val ACTION_STOP="STOP"; const val ACTION_OVERLAY_ON="OVERLAY_ON"; const val ACTION_OVERLAY_OFF="OVERLAY_OFF"
        const val EXTRA_TARGET="target"; const val EXTRA_PORT="port"; const val EXTRA_OVERLAY="overlay"
        private const val CHANNEL="proxy_service"; private const val ID=77
    }
    private var server:ProxyServer?=null
    private var overlay:TextView?=null
    private var count=0
    private var overlayOn=false

    override fun onCreate(){super.onCreate();createChannel();startForeground(ID,notification("Sulav Proxy • Background service"))}
    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
        when(i?.action){
            ACTION_START->{startServer(i.getStringExtra(EXTRA_TARGET)?:"https://example.com",i.getIntExtra(EXTRA_PORT,8080));if(i.getBooleanExtra(EXTRA_OVERLAY,false))showOverlay()}
            ACTION_STOP->{stopServer();stopSelf()}
            ACTION_OVERLAY_ON->showOverlay()
            ACTION_OVERLAY_OFF->hideOverlay()
        };return START_STICKY
    }
    private fun startServer(target:String,port:Int){if(server!=null)return;server=ProxyServer("0.0.0.0",port,target,{cap->count++;CaptureStore.append(filesDir,cap);updateOverlay()},{}) .also{it.start()}}
    private fun stopServer(){server?.stop();server=null;hideOverlay()}
    private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Proxy service",NotificationManager.IMPORTANCE_LOW))}
    private fun notification(text:String)=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Sulav Proxy").setContentText(text).setOngoing(true).build()
    private fun showOverlay(){if(!android.provider.Settings.canDrawOverlays(this)||overlay!=null)return;overlayOn=true;val wm=getSystemService(WINDOW_SERVICE) as WindowManager;overlay=TextView(this).apply{text="●  Sulav Proxy\n0 captures";textSize=12f;setTextColor(Color.WHITE);setPadding(22,14,22,14);setBackgroundColor(0xEE111827.toInt())};val type=if(android.os.Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE;val lp=WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,-3);lp.gravity=Gravity.TOP or Gravity.END;lp.x=20;lp.y=120;wm.addView(overlay,lp)}
    private fun updateOverlay(){overlay?.post{overlay?.text="●  Sulav Proxy\n$count captures"}}
    private fun hideOverlay(){val v=overlay?:return;runCatching{(getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)};overlay=null;overlayOn=false}
    override fun onDestroy(){stopServer();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
