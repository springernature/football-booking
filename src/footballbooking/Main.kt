package footballbooking

import org.http4k.client.ApacheClient
import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.cookies
import org.http4k.filter.ClientFilters
import org.http4k.filter.DebuggingFilters
import org.http4k.filter.TrafficFilters
import org.http4k.filter.cookie.BasicCookieStorage
import org.http4k.filter.cookie.CookieStorage
import org.http4k.filter.cookie.LocalCookie
import org.http4k.format.Gson.auto
import org.http4k.traffic.ReadWriteCache
import java.time.Clock
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
import java.time.temporal.TemporalAdjusters

data class Login(val Email: String, val Password: String, val PersistCookie: Boolean = true)

data class ListSessions(val BookingDate: String, val ActivityTypeGuid: String)

data class BookSlot(val ActivityTypeGuid: String, val SessionGuid: String, val Date: String)

data class BookingSessions(val Code: Int, val Data: List<Slot>) {
    fun findSlot() = Data.find { it.Availability == 0 && (it.StartDateTime.contains("T11:") || it.StartDateTime.contains("T12:")) }
}

data class Slot(
    val Guid: String,
    val Name: String,
    val StartDateTime: String,
    val EndDateTime: String,
    val Availability: Int
) {
    fun book(guid: String) = BookSlot(guid, Guid, StartDateTime)
}

val websiteUri = Uri.of("https://hsp.kingscross.co.uk")
val homePageRequest = Request(GET, "/")
val loginPageRequest = Request(GET, "/Accounts/Login.aspx")
val signInRequest = Request(POST, "/Services/Commercial/api/security/validatelogin.json")
val addBookingPageRequest = Request(GET, "/tools/commercial/muga/addsinglebooking.aspx")
val listSessionsRequest = Request(POST, "/Services/Commercial/api/muga/ListAvailableSessions.json")
val bookSessionRequest = Request(POST, "/Services/Commercial/api/muga/AddBooking.json")

val loginLens = Body.auto<Login>().toLens()
val listSessionsLens = Body.auto<ListSessions>().toLens()
val bookingSessionsLens = Body.auto<BookingSessions>().toLens()
val bookSlotLens = Body.auto<BookSlot>().toLens()

val footballGUID = "50ba1b7a-67f4-4c8d-a575-7dc8b5a43a30"


fun main(args: Array<String>) {
    val httpClient = ClientFilters.SetHostFrom(websiteUri)
        .then(Cookies())
        .then(DebuggingFilters.PrintRequestAndResponse())
        .then(ApacheClient())

    httpClient(homePageRequest)
    httpClient(loginPageRequest)
    httpClient(signInRequest.with(loginLens of Login(System.getenv("EMAIL"), System.getenv("PASSWORD"))))
    httpClient(addBookingPageRequest)

    val dayToBook = LocalDate.now().with(TemporalAdjusters.next(THURSDAY)).atStartOfDay(ZoneOffset.UTC)
    val dayToBookAsString = dayToBook.format(ISO_LOCAL_DATE_TIME)
    val response = httpClient(listSessionsRequest.with(listSessionsLens of ListSessions(dayToBookAsString, footballGUID)))

    val bookingSessions = bookingSessionsLens.extract(response)
    val session = bookingSessions.findSlot().printed()
    session ?: return println("No sessions available for booking")

    httpClient(bookSessionRequest.with(bookSlotLens of session.book(footballGUID))).printed()

    // replied with
    // {"Code":200,"Data":{"Guid":"65295e82-1373-43eb-bda3-31e0ac8e6635"}}
}

fun cached(httpHandler: HttpHandler): HttpHandler {
    val cache = ReadWriteCache.Disk("./traffic")
    return TrafficFilters.ServeCachedFrom(cache)
        .then(TrafficFilters.RecordTo(cache))
        .then(httpHandler)
}

/**
 * Needed this object because there was a problem with cookies in http4k
 * and it seems it hasn't been fixed yet.
 */
object Cookies {
    operator fun invoke(
        clock: Clock = Clock.systemDefaultZone(),
        storage: CookieStorage = BasicCookieStorage()
    ): Filter = Filter { next ->
        { request ->
            val now = clock.now()
            removeExpired(now, storage)
            val response = next(request.withLocalCookies(storage))
            storage.store(response.cookies().map { LocalCookie(it, now) })
            response
        }
    }

    private fun Request.withLocalCookies(storage: CookieStorage) =
        storage.retrieve()
            .map { it.cookie }
            .fold(this, { r, cookie -> r.cookie(cookie.name, cookie.value) })

    private fun removeExpired(now: LocalDateTime, storage: CookieStorage) =
        storage.retrieve()
            .filter { it.isExpired(now) }
            .forEach { storage.remove(it.cookie.name) }

    private fun Clock.now() = LocalDateTime.ofInstant(instant(), zone)

    private fun Request.cookie(name: String, value: String): Request = replaceHeader("Cookie", cookies().plus(Cookie(name, value)).toCookieString())

    private fun List<Cookie>.toCookieString() = map { "${it.name}=${it.value}" }.joinToString("; ")
}


fun <T: Any?> T.printed(): T? = this?.apply(::println)
