package app.iptvleger

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var connect: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        connect = findViewById(R.id.connect)
        status = findViewById(R.id.status)
        val clearCache: TextView = findViewById(R.id.clearCache)

        val current = Store.creds(this)
        if (current != null) {
            server.setText(current.server)
            user.setText(current.user)
            pass.setText(current.pass)
        } else {
            clearCache.visibility = View.GONE
        }

        connect.setOnClickListener { tryLogin() }
        clearCache.setOnClickListener {
            Api.clearCache(this)
            goHome()
        }
    }

    private fun tryLogin() {
        val c = Creds(
            Api.normalizeServer(server.text.toString()),
            user.text.toString().trim(),
            pass.text.toString().trim()
        )
        if (c.user.isEmpty() || c.pass.isEmpty() || c.server.length < 10) {
            status.text = "Remplissez les trois champs."
            return
        }
        connect.isEnabled = false
        status.text = "Connexion…"
        lifecycleScope.launch {
            val error = Api.login(c)
            connect.isEnabled = true
            if (error == null) {
                Store.saveCreds(this@LoginActivity, c)
                Api.clearCache(this@LoginActivity)
                goHome()
            } else {
                status.text = error
            }
        }
    }

    private fun goHome() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finish()
    }
}
