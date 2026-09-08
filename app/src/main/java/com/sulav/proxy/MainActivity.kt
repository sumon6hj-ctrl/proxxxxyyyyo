package com.sulav.proxy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val BG=0xFFF4F2FC.toInt(); private const val PANEL=0xFFFFFFFF.toInt(); private const val PANEL2=0xFFF0EEF8.toInt()
private const val PURPLE=0xFFFF7A00.toInt(); private const val PURPLE2=0xFFFF8F24.toInt(); private const val CYAN=0xFFE76500.toInt()
private const val TEXT=0xFF24232D.toInt(); private const val MUTED=0xFF7D7A88.toInt(); private const val GREEN=0xFF16834A.toInt(); private const val RED=0xFFD83A45.toInt()
private const val DEFAULT_TARGET="https://example.com"

data class Capture(val method:String,val url:String,val status:Int,val requestHex:String,val responseHex:String,val headers:String,val requestBytes:Long=0,val responseBytes:Long=0)

class MainActivity:Activity(){
 private val executor=Executors.newCachedThreadPool(); private val captures=CaptureStore.load(filesDir); private lateinit var content:LinearLayout
 private lateinit var packetCountView:TextView
 private var proxyRunning=false
 private var target=DEFAULT_TARGET
 private var overlayEnabled=false
 private var currentScreen="CAPTURE"
 private val screenStack=ArrayDeque<String>()
 private var lastBackAt=0L
 private var handlingBack=false
 private var pendingSaveText:String?=null
 private val mainHandler=Handler(Looper.getMainLooper())
 override fun onCreate(b:Bundle?){
  super.onCreate(b)
  window.statusBarColor=PURPLE
  window.navigationBarColor=Color.BLACK
  target=getPreferences(0).getString("target",target)?:target
  buildShell()
  screenStack.clear()
  showCapture(false)
  registerBackHandler()
 }

 @Suppress("DEPRECATION")
 override fun onBackPressed(){
  if(handlingBack) return
  handlingBack=true
  try { handleBackNow() } finally {
   mainHandler.postDelayed({ handlingBack=false }, 250L)
  }
 }

 private fun handleBackNow(){
  val focused=window.currentFocus
  if(focused is android.widget.EditText){
   val imm=getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
   imm.hideSoftInputFromWindow(focused.windowToken,0)
   focused.clearFocus()
   return
  }
  if(screenStack.isNotEmpty()){
   val previous=screenStack.removeLast()
   when(previous){
    "CAPTURE" -> showCapture(false)
    "CAPTURE_LOG" -> showCaptureLog(false)
    "DETAIL" -> showCaptureLog(false)
    "REQUEST" -> showRequest(false)
    "DECODER" -> showDecoder(false)
    "DECODED" -> showDecoded(lastDecodedBytes,false)
    "UPDATES" -> showUpdates(false)
    "ACCOUNT" -> showAccount(false)
    else -> { screenStack.clear(); showCapture(false) }
   }
   return
  }
  val now=System.currentTimeMillis()
  if(now-lastBackAt<1800L){
   lastBackAt=0L
   finish()
  }else{
   lastBackAt=now
   toast("Press back again to exit")
  }
 }
 private fun buildShell(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
  val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8));setBackgroundColor(PANEL)}
  top.addView(TextView(this).apply{text="●";textSize=24f;setTextColor(PURPLE)},LinearLayout.LayoutParams(dp(42),dp(54)))
  top.addView(TextView(this).apply{text="Sulav Proxy";textSize=20f;typeface=Typeface.DEFAULT_BOLD;setTextColor(TEXT)},LinearLayout.LayoutParams(0,dp(54),1f))
  packetCountView=TextView(this).apply{text="0 packets";textSize=11f;setTextColor(MUTED)}
  top.addView(packetCountView)
  content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
  root.addView(top);root.addView(ScrollView(this).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
  val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(PANEL);setPadding(dp(6),dp(6),dp(6),dp(6))}
  listOf("Proxy","Decoder","Settings").forEach{n->val v=TextView(this).apply{text=n.uppercase(Locale.US);textSize=11f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setTextColor(if((n=="Proxy"&&currentScreen in setOf("CAPTURE","CAPTURE_LOG","DETAIL","REQUEST"))||(n=="Decoder"&&currentScreen in setOf("DECODER","DECODED"))||(n=="Settings"&&currentScreen in setOf("UPDATES","ACCOUNT")))PURPLE else MUTED)};v.setOnClickListener{screenStack.clear();when(n){"Proxy"->showCapture(false);"Decoder"->showDecoder(false);"Settings"->showAccount(false)}};nav.addView(v,LinearLayout.LayoutParams(0,dp(64),1f))}
  root.addView(nav);setContentView(root)
 }
 private fun reset(t:String,s:String?=null){content.removeAllViews();addText(t,25f,TEXT,true,18,14);if(s!=null)addText(s,13f,MUTED,false,18,2)}
 private fun addText(s:String,size:Float,color:Int,bold:Boolean=false,left:Int=18,top:Int=8){content.addView(TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD;setPadding(dp(left),dp(top),dp(18),dp(5))})}
 private fun card():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(16));background=rounded(PANEL,0xFFE4E0EE.toInt(),1,20);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(18),dp(10),dp(18),dp(10))}}
 private fun rounded(fill:Int,stroke:Int,width:Int,radius:Int)=GradientDrawable().apply{setColor(fill);setStroke(dp(width),stroke);cornerRadius=dp(radius).toFloat()}
 private fun label(s:String){addText(s,12f,CYAN,true,36,2)}
 private fun edit(h:String,v:String="",multi:Boolean=false)=EditText(this).apply{hint=h;setText(v);textSize=14f;setTextColor(TEXT);setHintTextColor(0xFF9B98A7.toInt());setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(PANEL2);if(multi){minLines=6;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}else inputType=InputType.TYPE_CLASS_TEXT}
 private fun button(t:String,action:()->Unit)=Button(this).apply{text=t;textSize=12f;setTextColor(Color.WHITE);background=rounded(PURPLE,PURPLE,1,16);isAllCaps=false;setOnClickListener{action()};stateListAnimator=null;layoutParams=LinearLayout.LayoutParams(-1,dp(54)).apply{setMargins(0,dp(8),0,0)}}
 private fun addTextTo(p:LinearLayout,s:String,size:Float,color:Int,bold:Boolean=false){p.addView(TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(5),0,dp(5))})}
 private fun showCapture(push:Boolean=true){if(push&&currentScreen!="CAPTURE")screenStack.addLast(currentScreen);currentScreen="CAPTURE";reset("Proxy",if(proxyRunning) "RUNNING • 0.0.0.0:$proxyPort • HTTPS tunnel ready" else "STOPPED • background proxy service");val c=card();label("FORWARD TARGET");val e=edit("https://example.com",target);c.addView(e);c.addView(button("SAVE TARGET"){target=e.text.toString().trim();getPreferences(0).edit().putString("target",target).apply();toast("Target saved")});label("LOCAL CAPTURE");val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val start=button(if(proxyRunning)"STOP PROXY" else "START PROXY"){if(proxyRunning)stopProxy() else startProxy()};row.addView(start,LinearLayout.LayoutParams(0,dp(54),1f));val clear=button("CLEAR LOG"){CaptureStore.clear(filesDir);captures.clear();updatePacketCount();showCapture()};row.addView(clear,LinearLayout.LayoutParams(0,dp(54),1f));c.addView(row);content.addView(c);val actions=card();addTextTo(actions,"TOOLS",12f,CYAN,true);actions.addView(button("SEND REQUEST") { showRequest() });actions.addView(button("VIEW CAPTURED REQUESTS") { showCaptureLog() });content.addView(actions);addText(if(captures.isEmpty())"NO PACKETS YET\n\nStart the local proxy or send a test request to an endpoint you control." else "${captures.size} captured request(s)",14f,MUTED,false,20,22)}
 private val proxyPort:Int get()=getPreferences(0).getString("proxy_port","8080")?.toIntOrNull()?:8080
 private fun updatePacketCount(){if(::packetCountView.isInitialized)packetCountView.text="${captures.size} packets"}
 private fun startProxy(){
  val i=Intent(this,ProxyService::class.java).apply{action=ProxyService.ACTION_START;putExtra(ProxyService.EXTRA_TARGET,target);putExtra(ProxyService.EXTRA_PORT,proxyPort);putExtra(ProxyService.EXTRA_OVERLAY,overlayEnabled)}
  if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i); proxyRunning=true; updatePacketCount(); if(currentScreen=="ACCOUNT") showAccount(false) else showCapture(false)
 }
 private fun stopProxy(){stopService(Intent(this,ProxyService::class.java).apply{action=ProxyService.ACTION_STOP});proxyRunning=false;if(currentScreen=="ACCOUNT") showAccount(false) else showCapture(false)}
 private fun showCaptureLog(push:Boolean=true){if(push&&currentScreen!="CAPTURE_LOG")screenStack.addLast(currentScreen);currentScreen="CAPTURE_LOG";reset("CAPTURED PACKETS","${captures.size} request(s)");captures.asReversed().forEach{cap->val c=card();addTextTo(c,"${cap.method}  ${cap.status}",14f,if(cap.status in 200..399)GREEN else RED,true);addTextTo(c,cap.url,12f,TEXT);addTextTo(c,"${cap.requestBytes} B → ${cap.responseBytes} B",11f,MUTED);c.addView(button("VIEW DETAIL"){showDetail(cap)});content.addView(c)}}
 private fun showDetail(cap:Capture){
  if(currentScreen!="DETAIL")screenStack.addLast(currentScreen)
  currentScreen="DETAIL"
  reset("REQUEST DETAIL","${cap.method} ${cap.url}")
  val c=card()

  addTextTo(c,"STATUS ${cap.status}",14f,if(cap.status in 200..399)GREEN else RED,true)
  c.addView(copyButton("COPY STATUS","${cap.status}"))

  addTextTo(c,"URL",12f,CYAN,true)
  addTextTo(c,cap.url,11f,TEXT)
  c.addView(copyButton("COPY URL",cap.url))

  addTextTo(c,"HEADERS",12f,CYAN,true)
  addTextTo(c,cap.headers.ifBlank{"—"},11f,TEXT)
  c.addView(copyButton("COPY HEADERS",cap.headers))

  addTextTo(c,"REQUEST HEX",12f,CYAN,true)
  addTextTo(c,cap.requestHex.ifBlank{"—"},10f,TEXT)
  c.addView(copyButton("COPY REQUEST HEX",cap.requestHex))

  addTextTo(c,"RESPONSE HEX",12f,CYAN,true)
  addTextTo(c,cap.responseHex.ifBlank{"—"},10f,TEXT)
  c.addView(copyButton("COPY RESPONSE HEX",cap.responseHex))

  addTextTo(c,"SIZE",12f,CYAN,true)
  addTextTo(c,"Request: ${cap.requestBytes} B    Response: ${cap.responseBytes} B",11f,TEXT)

  c.addView(copyButton("COPY ALL","""METHOD: ${cap.method}
STATUS: ${cap.status}
URL: ${cap.url}

HEADERS:
${cap.headers}

REQUEST HEX:
${cap.requestHex}

RESPONSE HEX:
${cap.responseHex}

REQUEST BYTES: ${cap.requestBytes}
RESPONSE BYTES: ${cap.responseBytes}"""))

  c.addView(button("SAVE TO FILE"){
    pendingSaveText="""METHOD: ${cap.method}
STATUS: ${cap.status}
URL: ${cap.url}

HEADERS:
${cap.headers}

REQUEST HEX:
${cap.requestHex}

RESPONSE HEX:
${cap.responseHex}

REQUEST BYTES: ${cap.requestBytes}
RESPONSE BYTES: ${cap.responseBytes}
"""
    val safeName=cap.method.lowercase(Locale.US)+"_capture.txt"
    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
      addCategory(Intent.CATEGORY_OPENABLE)
      type="text/plain"
      putExtra(Intent.EXTRA_TITLE,safeName)
    },2001)
  })

  addTextTo(c,"HTTPS payload remains encrypted; only tunnel metadata and byte counts are shown.",10f,MUTED)
  content.addView(c)
 }

 private fun copyButton(title:String,value:String):Button=button(title){
  val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
  clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title,value))
  toast("Copied")
 }

 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==2001 && resultCode==RESULT_OK && data?.data!=null){
    val text=pendingSaveText ?: return
    try{
      contentResolver.openOutputStream(data.data!!)?.use{it.write(text.toByteArray(Charsets.UTF_8))}
      toast("Saved to file")
    }catch(e:Exception){
      toast("Save failed: ${e.message ?: "error"}")
    }finally{
      pendingSaveText=null
    }
  }
 }
 private fun showRequest(push:Boolean=true){if(push&&currentScreen!="REQUEST")screenStack.addLast(currentScreen);currentScreen="REQUEST";reset("REQUEST SEND","Send a request to an endpoint you control.");val c=card();label("REQUEST BLOCK");val m=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("GET","POST","PUT","DELETE"))};c.addView(m);val u=edit("URL",target);c.addView(u);val h=edit("Headers (Name: Value per line)","",true);c.addView(h);val body=edit("Payload / body","",true);c.addView(body);c.addView(button("SEND REQUEST"){sendRequest(m.selectedItem.toString(),u.text.toString().trim(),h.text.toString(),body.text.toString())});content.addView(c);addText("For safety, this sender does not extract authentication tokens or modify third-party game traffic.",12f,MUTED,false,20,12)}
 private fun sendRequest(method:String,urlText:String,headerText:String,bodyText:String){executor.execute{var conn:HttpURLConnection?=null;try{conn=URL(urlText).openConnection() as HttpURLConnection;conn!!.requestMethod=method;conn!!.connectTimeout=12000;conn!!.readTimeout=12000;headerText.lines().forEach{p->val i=p.indexOf(':');if(i>0)conn!!.setRequestProperty(p.substring(0,i).trim(),p.substring(i+1).trim())};if(method!="GET"&&method!="DELETE"){conn!!.doOutput=true;conn!!.outputStream.use{it.write(bodyText.toByteArray())}};val status=conn!!.responseCode;val stream=if(status>=400)conn!!.errorStream else conn!!.inputStream;val bytes=stream?.let{BufferedInputStream(it).use{inp->readAll(inp)}}?:ByteArray(0);val safe=headerText.lines().joinToString("\n"){if(it.lowercase(Locale.US).startsWith("authorization:"))"Authorization: [REDACTED]" else it};val cap=Capture(method,urlText,status,hex(bodyText.toByteArray(),512),hex(bytes,512),safe);synchronized(captures){captures.add(cap)};runOnUiThread{updatePacketCount();toast("Response $status");if(currentScreen=="REQUEST")showDetail(cap)}}catch(e:Exception){runOnUiThread{toast("Request failed: ${e.message?:"error"}")}}finally{conn?.disconnect()}}}
 private fun showDecoder(push:Boolean=true){if(push&&currentScreen!="DECODER")screenStack.addLast(currentScreen);currentScreen="DECODER";reset("PROTOBUF DECODER","Decode generic response bytes supplied by you.");val c=card();label("RESPONSE HEX");val i=edit("08 03 12 …","",true);c.addView(i);c.addView(button("DECODE"){showDecoded(decodeHex(i.text.toString()))});c.addView(button("CLEAR"){i.setText("")});content.addView(c);addText("DECODED JSON / FALLBACK",12f,CYAN,true,20,16);addText("—",12f,TEXT,false,20,6)}
 private var lastDecodedBytes=ByteArray(0)
 private fun showDecoded(b:ByteArray,push:Boolean=true){lastDecodedBytes=b;if(push&&currentScreen!="DECODED")screenStack.addLast(currentScreen);currentScreen="DECODED";reset("PROTOBUF DECODER","Decoded byte content");val c=card();addTextTo(c,"DECODED / FALLBACK",12f,CYAN,true);addTextTo(c,b.toString(Charsets.UTF_8).ifBlank{"—"},12f,TEXT);addTextTo(c,"HEX",12f,CYAN,true);addTextTo(c,hex(b,4096),10f,TEXT);content.addView(c);c.addView(button("COPY HEX"){val cm=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager;cm.setPrimaryClip(android.content.ClipData.newPlainText("hex",hex(b,4096)));toast("Copied")})}
 private fun showUpdates(push:Boolean=true){if(push&&currentScreen!="UPDATES")screenStack.addLast(currentScreen);currentScreen="UPDATES";reset("UPDATES","App update information");val c=card();addTextTo(c,"You are up to date",22f,PURPLE,true);addTextTo(c,"Version 1.0 is the current build.",15f,TEXT);c.addView(button("CHECK AGAIN"){toast("No update endpoint configured")});content.addView(c)}
 private fun showAccount(push:Boolean=true){if(push&&currentScreen!="ACCOUNT")screenStack.addLast(currentScreen);currentScreen="ACCOUNT";reset("Settings","Background service, updates and capture controls");val c=card();addTextTo(c,"BACKGROUND PROXY",12f,CYAN,true);addTextTo(c,if(proxyRunning)"Running in foreground service" else "Stopped",16f,if(proxyRunning)GREEN else MUTED,true);c.addView(button(if(proxyRunning)"STOP BACKGROUND PROXY" else "START BACKGROUND PROXY"){if(proxyRunning)stopProxy() else startProxy()});c.addView(button(if(overlayEnabled)"DISABLE FLOATING CAPTURE" else "ENABLE FLOATING CAPTURE"){if(!android.provider.Settings.canDrawOverlays(this)){startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));toast("Allow display over other apps, then enable again")}else{overlayEnabled=!overlayEnabled;val a=Intent(this,ProxyService::class.java).apply{action=if(overlayEnabled)ProxyService.ACTION_OVERLAY_ON else ProxyService.ACTION_OVERLAY_OFF};startService(a);showAccount(false)}});c.addView(button("APP UPDATES") { showUpdates() });content.addView(c);val s=card();addTextTo(s,"HTTPS CAPTURE",12f,CYAN,true);addTextTo(s,"CONNECT tunnel support is enabled. HTTPS stays encrypted end-to-end; the app records destination metadata, status and byte counts without decrypting credentials.",12f,TEXT);addTextTo(s,"Traffic is persistent and is not automatically deleted.",12f,GREEN,true);content.addView(s);val p=card();addTextTo(p,"PROXY SETUP",12f,CYAN,true);addTextTo(p,"Host: 127.0.0.1    Port: $proxyPort",15f,TEXT,true);addTextTo(p,"Configure the device/app you control to use this HTTP proxy. HTTPS uses CONNECT tunneling.",12f,MUTED);content.addView(p);val a=card();addTextTo(a,"PROFILE",12f,CYAN,true);addTextTo(a,"Sulav Proxy Owner",20f,TEXT,true);addTextTo(a,"Local profile • Device bound",13f,MUTED);a.addView(button("CHANGE PHOTO"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},1001)});content.addView(a)}
 private fun decodeHex(s:String):ByteArray{val c=s.replace("0x","",true).replace(Regex("[^0-9A-Fa-f]"),"");if(c.length%2!=0)return ByteArray(0);return ByteArray(c.length/2){i->c.substring(i*2,i*2+2).toInt(16).toByte()}}
 private fun hex(b:ByteArray,n:Int)=b.copyOfRange(0,minOf(b.size,n)).joinToString(" "){String.format("%02X",it)}
 private fun readAll(i:BufferedInputStream):ByteArray{val o=ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=i.read(b);if(n<=0)break;o.write(b,0,n)};return o.toByteArray()}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show();private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
