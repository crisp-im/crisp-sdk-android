package im.crisp.sdk.model

/**
 * The company of the user
 * @see im.crisp.sdk.api.CrispSessionController.User.setCompany
 * @see im.crisp.sdk.api.CrispChatboxController.User.getCompany
 */
data class Company @JvmOverloads constructor(
    val name: String,
    val url: String? = null,
    val description: String? = null,
    val employment: Employment? = null,
    val geolocation: Geolocation? = null
) {
    data class Employment @JvmOverloads constructor(
        val role: String,
        val title: String? = null
    )

    data class Geolocation @JvmOverloads constructor(
        val city: String? = null,
        val country: String? = null
    )
}
