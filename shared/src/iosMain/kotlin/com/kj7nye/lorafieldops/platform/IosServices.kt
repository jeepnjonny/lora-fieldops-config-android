@file:OptIn(ExperimentalForeignApi::class)

package com.kj7nye.lorafieldops.platform

import com.kj7nye.lorafieldops.logger.HttpPoster
import com.kj7nye.lorafieldops.logger.NetworkException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// -----------------------------------------------------------------------------
// HTTP
// -----------------------------------------------------------------------------

/** Refuses redirects: one would drop the POST body and could send the key to another host. */
private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) = completionHandler(null)
}

/** [HttpPoster] on NSURLSession. Never logs the key. */
internal class IosHttpPoster : HttpPoster {
    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            timeoutIntervalForRequest = 30.0
            timeoutIntervalForResource = 60.0
        },
        delegate = NoRedirects(),
        delegateQueue = null,
    )

    override suspend fun post(url: String, bearer: String, jsonBody: String): Pair<Int, String> {
        val nsUrl = NSURL.URLWithString(url) ?: throw NetworkException("Not a valid address")
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("POST")
            setValue("Bearer $bearer", forHTTPHeaderField = "Authorization")
            setValue("application/json; charset=utf-8", forHTTPHeaderField = "Content-Type")
            setValue("application/json", forHTTPHeaderField = "Accept")
            setHTTPBody(jsonBody.encodeToByteArray().toNSData())
        }
        return suspendCancellableCoroutine { cont ->
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                if (error != null) {
                    cont.resumeWithException(NetworkException(error.localizedDescription))
                } else {
                    val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: -1
                    cont.resume(code to (data?.toByteArray()?.decodeToString() ?: ""))
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }
}

// -----------------------------------------------------------------------------
// Location
// -----------------------------------------------------------------------------

/** One-shot fix from CoreLocation; asks for "while using" permission the first time. */
internal class IosLocationSource : LocationSource {
    override suspend fun currentFix(): LocationResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val manager = CLLocationManager()
            val delegate = LocationDelegate { result ->
                manager.delegate = null
                if (cont.isActive) cont.resume(result)
            }
            manager.delegate = delegate
            manager.desiredAccuracy = kCLLocationAccuracyBest
            cont.invokeOnCancellation {
                // Keep the delegate alive until here (the manager only holds it weakly).
                delegate.cancelled = true
                manager.stopUpdatingLocation()
            }
            delegate.start(manager)
        }
    }
}

private const val DENIED =
    "Location access is off for this app. Allow it in Settings → Privacy & Security → Location Services."

private class LocationDelegate(private val done: (LocationResult) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    var cancelled = false
    private var requested = false

    fun start(manager: CLLocationManager) {
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()   // answer arrives below
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> request(manager)
            else -> done(LocationResult.Failed(DENIED))
        }
    }

    private fun request(manager: CLLocationManager) {
        if (requested || cancelled) return
        requested = true
        manager.requestLocation()
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusNotDetermined -> Unit
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> request(manager)
            else -> done(LocationResult.Failed(DENIED))
        }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val (lat, lon) = loc.coordinate.useContents { latitude to longitude }
        done(
            LocationResult.Fix(
                latitude = lat,
                longitude = lon,
                altitude = if (loc.verticalAccuracy > 0) loc.altitude else null,
                accuracyM = if (loc.horizontalAccuracy > 0) loc.horizontalAccuracy.toFloat() else null,
            )
        )
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        done(LocationResult.Failed("Couldn't get a location fix: ${didFailWithError.localizedDescription}"))
    }
}

// -----------------------------------------------------------------------------
// Share sheet
// -----------------------------------------------------------------------------

internal fun presentShareSheet(text: String) {
    val root = topViewController() ?: return
    val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    // iPad shows the sheet as a popover, which needs an anchor.
    sheet.popoverPresentationController?.sourceView = root.view
    root.presentViewController(sheet, animated = true, completion = null)
}

@Suppress("DEPRECATION")   // keyWindow: fine for a single-window app
private fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}
