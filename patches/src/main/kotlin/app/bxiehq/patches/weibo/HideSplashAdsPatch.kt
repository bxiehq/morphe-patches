package app.bxiehq.patches.weibo

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch

private const val FLASH_AD = "Lcom/sina/weibo/mobileads/view/FlashAd;"

/** Blocks presentation while preserving the loading/readiness callbacks startup waits for. */
@Suppress("unused")
val hideSplashAdsPatch = patch(
    name = "Hide splash ads",
    description = "Skips full-screen ads on cold launch and return from the background.",
    default = true,
) {
    compatibleWith(weiboCompatibility)

    execute {
        listOf(emptyList(), listOf("J", "J")).forEach { parameters ->
            Fingerprint(
                definingClass = FLASH_AD,
                name = "isSwitchBackgroundTimeout",
                parameters = parameters,
                returnType = "Z",
            ).method.addInstructions(0, """
                const/4 v0, 0x0
                return v0
            """.trimIndent())
        }

        Fingerprint(
            definingClass = FLASH_AD,
            name = "loadAndShowAd",
            parameters = listOf("Lcom/sina/weibo/mobileads/d/f;", "Landroid/app/Activity;"),
            returnType = "V",
        ).method.addInstructions(0, """
            iput-object p1, p0, $FLASH_AD->flashAdLoadManager:Lcom/sina/weibo/mobileads/d/f;
            iget-object v0, p0, $FLASH_AD->listener:Lcom/sina/weibo/mobileads/controller/AdListener;
            if-eqz v0, :weibo_no_fill_done
            sget-object v1, Lcom/sina/weibo/mobileads/model/AdRequest${'$'}ErrorCode;->NO_FILL:Lcom/sina/weibo/mobileads/model/AdRequest${'$'}ErrorCode;
            invoke-interface {v0, p0, v1}, Lcom/sina/weibo/mobileads/controller/AdListener;->onFailedToReceiveAd(Lcom/sina/weibo/mobileads/view/IAd;Lcom/sina/weibo/mobileads/model/AdRequest${'$'}ErrorCode;)V
            :weibo_no_fill_done
            return-void
        """.trimIndent())

        Fingerprint(
            definingClass = FLASH_AD,
            name = "showFromLoadManager",
            parameters = listOf("Landroid/app/Activity;", "Landroid/content/Intent;"),
            returnType = "V",
        ).method.addInstructions(0, """
            invoke-direct {p0, p1}, $FLASH_AD->setActivity(Landroid/app/Activity;)V
            iput-object p2, p0, $FLASH_AD->nextIntent:Landroid/content/Intent;
            const/4 v0, 0x0
            iput-boolean v0, p0, $FLASH_AD->isSkiped:Z
            invoke-virtual {p0, v0}, $FLASH_AD->onSkip(Z)V
            return-void
        """.trimIndent())

        mapOf(
            "Lcom/sina/weibo/mobileads/view/FlashAdActivity;" to "Landroid/app/Activity;",
            "Lcom/sina/weibo/mobileads/view/FlashAdAlphaActivity;" to "Lcom/sina/weibo/mobileads/view/FlashAdActivity;",
        ).forEach { (activity, parent) ->
            Fingerprint(
                definingClass = activity,
                name = "onCreate",
                parameters = listOf("Landroid/os/Bundle;"),
                returnType = "V",
            ).method.addInstructions(0, """
                invoke-super {p0, p1}, $parent->onCreate(Landroid/os/Bundle;)V
                invoke-virtual {p0}, Landroid/app/Activity;->finish()V
                return-void
            """.trimIndent())
        }
    }
}
