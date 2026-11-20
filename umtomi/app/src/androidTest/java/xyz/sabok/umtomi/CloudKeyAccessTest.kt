package xyz.sabok.umtomi

import android.accounts.Account
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.sync.CloudAccount
import xyz.sabok.umtomi.sync.shared.DriveAppDataClient

/** Reads the Browser's existing Drive key file by ID without logging its contents. */
@RunWith(AndroidJUnit4::class)
class CloudKeyAccessTest {
    @Test fun readOtherAppReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = InstrumentationRegistry.getArguments().getString("shelfRun").orEmpty()
        assumeTrue(token.matches(Regex("[0-9a-f]{32}")))
        val file = File(Environment.getExternalStorageDirectory(), "Omyaru_Bookshelf/.validation-$token/reference.json")
        assumeTrue(file.isFile)
        val account = CloudAccount.account(context)
        if (account == null) { File(file.parentFile, "status.txt").writeText("not_connected"); return }
        val request = AuthorizationRequest.builder().setAccount(Account(account!!, "com.google"))
            .setRequestedScopes(listOf(Scope(DriveAppDataClient.SCOPE))).setOptOutIncludingGrantedScopes(true).build()
        val auth = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 25, TimeUnit.SECONDS)
        if (auth.hasResolution() || auth.accessToken == null) { File(file.parentFile, "status.txt").writeText("consent_required"); return }
        val client = DriveAppDataClient { auth.accessToken!! }.apply { accountMode = true }
        val reference = JSONObject(file.readText())
        val contents = client.read(DriveAppDataClient.Entry(reference.getString("id"), reference.getString("name"), null))
        check(contents.isNotBlank())
        check(file.delete())
        check(file.parentFile!!.delete())
    }
}
