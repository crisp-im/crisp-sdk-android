package im.crisp.sdk.internal.bridge.types

import im.crisp.sdk.internal.bridge.js.jsEncode
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyTest {
    private val company = Company(
        url = "https://crisp.chat/",
        description = "Give your customer experience a human touch.",
        employment = Employment(title = "CTO", role = "Software Engineer"),
        geolocation = Geolocation(city = "Nantes", country = "FR"),
    )

    @Test fun `employment and geolocation are encoded as arrays`() {
        assertEquals(
            """{"url":"https://crisp.chat/","description":"Give your customer experience a human touch.",""" +
                """"employment":["CTO","Software Engineer"],"geolocation":["FR","Nantes"]}""",
            jsEncode(company),
        )
        assertEquals(
            """{"geolocation":["FR",null]}""",
            jsEncode(Company(geolocation = Geolocation(country = "FR"))),
        )
    }

    @Test fun `employment and geolocation are decoded from arrays and objects`() {
        assertEquals(
            company.copy(name = "Crisp"),
            bridgeJson.decodeFromString<Company>(
                """{"name":"Crisp","url":"https://crisp.chat/","description":"Give your customer experience a human touch.",""" +
                    """"employment":["CTO","Software Engineer"],"geolocation":["FR","Nantes"]}""",
            ),
        )
        assertEquals(
            Company(name = "Crisp", employment = Employment(role = "CTO"), geolocation = Geolocation(country = "FR")),
            bridgeJson.decodeFromString<Company>(
                """{"name":"Crisp","employment":{"role":"CTO"},"geolocation":["FR",null]}""",
            ),
        )
    }
}
