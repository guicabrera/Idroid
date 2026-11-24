package com.idroid.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * This phone's SSH identity for the paired Macs (ADR-0005): an RSA key made
 * on first use, kept in EncryptedSharedPreferences. Its public half is put
 * in the Mac's authorized_keys through the relay, so there is nothing to
 * copy by hand.
 */
class SshKeyStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "idroid_ssh",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    fun keyPair(): Pair<ByteArray, String> {
        val private = prefs.getString("private", null)
        val public = prefs.getString("public", null)
        if (private != null && public != null) return private.toByteArray() to public
        // RSA because JSch generates it without extra crypto providers on every Android version.
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val privateOut = ByteArrayOutputStream().also { pair.writePrivateKey(it) }
        val publicOut = ByteArrayOutputStream().also { pair.writePublicKey(it, "idroid@" + android.os.Build.MODEL.replace(' ', '-')) }
        pair.dispose()
        prefs.edit().putString("private", privateOut.toString()).putString("public", publicOut.toString().trim()).apply()
        return privateOut.toByteArray() to publicOut.toString().trim()
    }

    val publicKey get() = keyPair().second
}

/**
 * An interactive shell on the Mac over the tailnet: a PTY channel whose
 * output goes to [onOutput] (on a background thread) and whose input comes
 * from [write]. Connecting blocks, so call [connect] off the main thread.
 */
class SshSession(
    private val host: String,
    private val user: String,
    private val keys: SshKeyStore,
    private val knownHosts: File,
    private val onOutput: (ByteArray, Int) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var input: OutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closing = false

    fun connect(cols: Int, rows: Int) {
        val jsch = JSch()
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val (private, _) = keys.keyPair()
        jsch.addIdentity("idroid", private, null, null)
        val s = jsch.getSession(user, host, 22)
        // First connection: trust and remember the Mac's host key. A changed
        // key later fails loudly (JSch refuses it) instead of being accepted.
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "publickey")
        s.userInfo = TrustNewHost
        s.setServerAliveInterval(15_000)
        s.connect(15_000)
        val c = s.openChannel("shell") as ChannelShell
        c.setPtyType("xterm-256color", cols, rows, 0, 0)
        c.setEnv("LANG", "en_US.UTF-8")
        val out: InputStream = c.inputStream
        input = c.outputStream
        c.connect(10_000)
        session = s
        channel = c
        Thread({
            val buffer = ByteArray(16 * 1024)
            var error: String? = null
            try {
                while (true) {
                    val n = out.read(buffer)
                    if (n < 0) break
                    if (n > 0) onOutput(buffer.copyOf(n), n)
                }
            } catch (e: Exception) {
                if (!closing) error = e.message
            }
            onClosed(error)
            close()
        }, "ssh-reader").start()
    }

    fun write(data: ByteArray) {
        val stream = input ?: return
        writer.execute { runCatching { stream.write(data); stream.flush() } }
    }

    fun resize(cols: Int, rows: Int) {
        val c = channel ?: return
        writer.execute { runCatching { c.setPtySize(cols, rows, 0, 0) } }
    }

    fun close() {
        closing = true
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        writer.shutdown()
    }

    private object TrustNewHost : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = true
        override fun showMessage(message: String?) {}
    }
}
