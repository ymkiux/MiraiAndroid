package io.github.mzdluo123.mirai.android.ui.console

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import io.github.mzdluo123.mirai.android.AppSettings
import io.github.mzdluo123.mirai.android.IConsole
import io.github.mzdluo123.mirai.android.R
import io.github.mzdluo123.mirai.android.service.ServiceConnector
import io.github.mzdluo123.mirai.android.utils.shareText
import kotlinx.android.synthetic.main.fragment_home.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.toast.toast
import java.security.MessageDigest


class ConsoleFragment : Fragment() {
    companion object {
        const val TAG = "ConsoleFragment"
    }


    lateinit var conn: ServiceConnector

    private var autoScroll = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        conn = ServiceConnector(requireContext())
        lifecycle.addObserver(conn)
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        setHasOptionsMenu(true)
        return root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        commandSend_btn.setOnClickListener {
            submitCmd()
        }
        shortcutBottom_btn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (autoScroll) {
                    autoScroll = false
                    toast("自动滚动禁用")
                    shortcutBottom_btn.setImageResource(R.drawable.ic_keyboard_arrow_down_black_24dp)
                } else {
                    autoScroll = true
                    toast("自动滚动启用")
                    shortcutBottom_btn.setImageResource(R.drawable.ic_baseline_keyboard_arrow_up_24)
                }

            }
        }
        if (AppSettings.waitingDebugger && conn.connectStatus.value == false) {
            log_text.text = "正在等待调试器链接,PID见日志....."
        }
        command_input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCmd()
            }
            return@setOnEditorActionListener false
        }
    }

    private fun refreshLog() {
        // 将新的log显示到屏幕
        conn.registerConsole(object : IConsole.Stub() {
            override fun newLog(log: String) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    log_text.append("\n")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        log_text?.append(Html.fromHtml(log, Html.FROM_HTML_MODE_COMPACT))
                    } else {
                        log_text?.append(Html.fromHtml(log))
                    }
                    if (autoScroll) {
                        delay(20)
                        main_scroll.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }
            }
        })
        // 首次启动加载缓存中的log
        conn.connectStatus.observe(viewLifecycleOwner, {
            Log.d(TAG, "service status $it")
            if (it) {
                lifecycleScope.launch(Dispatchers.Default) {
                    var text = conn.botService.log.joinToString(separator = "<br>")
                    withContext(Dispatchers.Main) {
                        text = text.replace("null<br>null<br>", "").replace("\n", "")
                            .replace("null", "").trim()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            log_text?.text = Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT)
                        } else {
                            log_text?.text = Html.fromHtml(text)
                        }
                        if (autoScroll) {
                            delay(20)
                            main_scroll.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        startLoadAvatarAndLog()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_console, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_setAutoLogin -> {
                setAutoLogin()
            }
            R.id.action_report -> context?.shareText(
                buildString {
                    append(conn.botService.botInfo)
                    append("\n")
                    append("========以下是控制台log=======\n")
                    append(conn.botService.log.joinToString(separator = "\n"))
                }, lifecycleScope
            )
            R.id.action_clean -> {
                log_text.text = ""
                conn.botService.clearLog()
            }

            R.id.action_battery -> {
                ignoreBatteryOptimization(requireActivity())
            }
            /*
                    R.id.action_fast_restart -> {
                        NotificationFactory.dismissAllNotification()
                        restart()
                    }*/
        }
        return false
    }

    @SuppressLint("BatteryLife")
    private fun ignoreBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager =
                getSystemService(requireContext(), PowerManager::class.java)
            //  判断当前APP是否有加入电池优化的白名单，如果没有，弹出加入电池优化的白名单的设置对话框。

            val hasIgnored = powerManager!!.isIgnoringBatteryOptimizations(activity.packageName)
            if (!hasIgnored) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:" + activity.packageName)
                startActivity(intent)
            } else {
                Toast.makeText(context, "您已授权忽略电池优化", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "你的设备无需执行此操作", Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitCmd() {
        var command = command_input.text.toString()

        if (!command.startsWith("/")) {
            command = "/$command"
        }
        try {
            lifecycleScope.launch(Dispatchers.Default) {
                conn.botService.runCmd(command)
            }
            command_input.text.clear()
        } catch (e: DeadObjectException) {
            toast("服务状态异常，请在菜单内点击快速重启")
        }

    }

    private fun setAutoLogin() {
        val alertView = View.inflate(activity, R.layout.dialog_autologin, null)
        val pwdInput = alertView.findViewById<EditText>(R.id.password_input)
        val qqInput = alertView.findViewById<EditText>(R.id.qq_input)
        val accountStore = requireActivity().getSharedPreferences("account", Context.MODE_PRIVATE)
        val dialog = AlertDialog.Builder(activity)
            .setView(alertView)
            .setCancelable(true)
            .setTitle("设置自动登录")
            .setPositiveButton("设置自动登录") { _, _ ->
                accountStore.edit().putLong("qq", qqInput.text.toString().toLong())
                    .putString("pwd", md5(pwdInput.text.toString())).apply()
                Toast.makeText(activity, "设置成功,重启后生效", Toast.LENGTH_SHORT).show()
            }

            .setNegativeButton("取消自动登录") { _, _ ->
                accountStore.edit().putLong("qq", 0L).putString("pwd", "").apply()
                Toast.makeText(activity, "设置成功,重启后生效", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
        dialog.show()
    }

    private fun startLoadAvatarAndLog() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                refreshLog()
                try {
                    val id = conn.botService.logonId
                    if (id != 0L) {
                        Glide.with(requireActivity())
                            .load("http://q1.qlogo.cn/g?b=qq&nk=$id&s=640")
                            .apply(
                                RequestOptions().error(R.mipmap.ic_new_launcher_round)
                                    .transform(RoundedCorners(40))
                            )
                            .into(requireActivity().findViewById(R.id.head_imageVIew))
                        return@launch
                    }

                } catch (e: UninitializedPropertyAccessException) {
                    // pass
                } catch (e: DeadObjectException) {
                    //pass
                }
                delay(1000)
            }
        }
    }


    private fun md5(str: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val result = digest.digest(str.toByteArray())
        //没转16进制之前是16位
        println("result${result.size}")
        //转成16进制后是32字节
        return toHex(result)
    }

    private fun toHex(byteArray: ByteArray): String {
        //转成16进制后是32字节
        return with(StringBuilder()) {
            byteArray.forEach {
                val hex = it.toInt() and (0xFF)
                val hexStr = Integer.toHexString(hex)
                if (hexStr.length == 1) {
                    append("0").append(hexStr)
                } else {
                    append(hexStr)
                }
            }
            toString()
        }
    }

}



