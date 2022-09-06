package net.cyclestreets.routing

import android.content.Context
import android.os.AsyncTask
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.ObjectMapper
import net.cyclestreets.RoutePlans.PLAN_LEISURE
import net.cyclestreets.api.JourneyPlanner
import net.cyclestreets.content.RouteData
import net.cyclestreets.util.Dialog
import net.cyclestreets.util.Logging
import net.cyclestreets.util.ProgressDialog
import net.cyclestreets.view.R
import org.json.JSONArray
import org.json.JSONObject
import java.lang.StringBuilder
import java.text.SimpleDateFormat
import java.util.*

abstract class RoutingTask<Params> protected constructor(private val initialMsg: String,
                                                         private val context: Context) : AsyncTask<Params, Int, RouteData>() {
    private val objectMapper: ObjectMapper = ObjectMapper()
    private var progress: ProgressDialog? = null
    private var error: String? = null
    private val TAG = Logging.getTag(RoutingTask::class.java)
    private val NO_ITINERARY = -1L
    private var rtAltRoute: Boolean = false

    protected constructor(progressMessageId: Int,
                          context: Context, cAltRoute: Boolean = false) : this(context.getString(progressMessageId), context)
                          {rtAltRoute = cAltRoute}

    protected fun fetchRoute(routeType: String,
                             itinerary: Long = NO_ITINERARY,
                             speed: Int,
                             waypoints: Waypoints? = null,
                             distance: Int? = null,
                             duration: Int? = null,
                             poiTypes: String? = null,
                             saveRoute: Boolean = true): RouteData? {
        Log.d(TAG, "Starting fetchRoute")
        return try {
            val json = doFetchRoute(routeType, itinerary, speed, waypoints, distance, duration, poiTypes)
            val returnedJson = reformatJson(json);
            when {
                (json == "null") -> {
                    error = context.getString(R.string.route_not_found)
                    throw ErrorFromServerException(error!!)
                }
                containsError(json) -> {
                    // Show the error message returned by the server
                    throw ErrorFromServerException(error!!)
                }
                else ->
                    RouteData(returnedJson, waypoints, null, saveRoute)
            }
        } catch (e: Exception) {
            if (error == null) {
                error = context.getString(R.string.could_not_contact_server) + " " + e.message
            }
            Log.w(TAG, error, e)
            null
        }
    }

    private fun doFetchRoute(routeType: String,
                             itinerary: Long,
                             speed: Int,
                             waypoints: Waypoints?,
                             distance: Int?,
                             duration: Int?,
                             poiTypes: String?): String {
        return when {
            itinerary != NO_ITINERARY -> getRoutebyItineraryNo(routeType, itinerary)
            routeType == PLAN_LEISURE -> JourneyPlanner.getCircularJourneyJson(waypoints, distance, duration, poiTypes)
            else -> JourneyPlanner.getOpenJourneyJson("5b3ce3597851110001cf62488925edfcceb2426d99547213cb080f2b", waypoints!!)
        }
    }

    private fun getRoutebyItineraryNo(routeType: String, itinerary: Long): String {
        val json = JourneyPlanner.retrievePreviousJourneyJson(routeType, itinerary)
        return if (json != "null" && !containsError(json))
            json
        else {
            // In normal circumstance, this branch should only be hit on retrieving a route by ID,
            // where type is not known.
            // Clear the error, and try again to see if there is a leisure (circular) route with this number
            error = null
            JourneyPlanner.retrievePreviousJourneyJson(PLAN_LEISURE, itinerary)
        }
    }

    private fun containsError(json: String): Boolean {
        val jsonNode = objectMapper.readTree(json)
        error = jsonNode.get("Error")?.asText(context.getString(R.string.route_not_found))
        return (error != null)
    }

    override fun onPreExecute() {
        super.onPreExecute()
        Log.d(TAG, "RoutingTask PreExecute")
        // Don't show progress or block input for alternative route
        if (!rtAltRoute) {
            try {
                progress = Dialog.createProgressDialog(context, initialMsg)
                progress!!.show()
            } catch (e: Exception) {
                progress = null
            }
        }
    }

    override fun onProgressUpdate(vararg p: Int?) {
        Log.d(TAG, "RoutingTask onProgressUpdate")
        if (!rtAltRoute)
            progress?.setMessage(context.getString(p[0]!!))
    }

    override fun onPostExecute(route: RouteData?) {
        Log.d(TAG, "Start RoutingTask onPostExecute")
        if (rtAltRoute) {
            Route.doOnNewAltJourney(route)
        }
        else
        {
            if (route != null)
                Route.onNewJourney(route)
            progressDismiss()
        }

        if (error != null)
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        Log.d(TAG, "RoutingTask Finish onPostExecute")
    }

    private fun progressDismiss() {
        try {
            // some devices, in rare situations, can throw here so just catch and swallow
            progress?.dismiss()
        } catch (e: Exception) {}
    }

    private fun reformatJson(json: String): String {
        //JSON from server
        val jsonObj = JSONObject(json)
        val featuresArr = jsonObj.get("features") as JSONArray
        val feature = featuresArr.get(0) as JSONObject
        val bbox = feature.get("bbox") as JSONArray
        val properties = feature.get("properties") as JSONObject
        val segmentsArr = properties.get("segments") as JSONArray
        val segment = segmentsArr.get(0) as JSONObject
        val stepArr = segment.get("steps") as JSONArray
        val geometry = feature.get("geometry") as JSONObject
        val coordinates = geometry.get("coordinates") as JSONArray


        //Mapping to app json
        //Returning json
        val returnedJson = JSONObject();
        //add waypoint
        val waypoints = JSONArray()
        var waypoint1 = JSONObject();
        waypoint1.put("latitude",""+bbox.get(0))
        waypoint1.put("longitude",""+bbox.get(3))
        waypoint1.put("sequenceId","1");
        waypoints.put(0,waypoint1)
        val waypoint2 = JSONObject();
        waypoint2.put("latitude",""+bbox.get(1))
        waypoint2.put("longitude",""+bbox.get(2))
        waypoint2.put("sequenceId","2");
        waypoints.put(1,waypoint2);
        returnedJson.put("waypoints",waypoints);
        //add route
        val route = JSONObject();
        val firstStep = stepArr.get(0) as JSONObject;
        val lastStep = stepArr.get(stepArr.length()-1) as JSONObject;
        route.put("start",firstStep.get("name"))
        route.put("finish",lastStep.get("name"))
        route.put("startBearing","0")
        route.put("startSpeed","0")
        route.put("start_longitude",""+bbox.get(0))
        route.put("start_latitude",""+bbox.get(3))
        route.put("finish_longitude",""+bbox.get(2))
        route.put("finish_latitude",""+bbox.get(1))
        route.put("crow_fly_distance",""+segment.get("distance"))
        route.put("event","depart")
        route.put("whence","1662344245")
        route.put("speed","20")
        route.put("itinerary","86294665")
        route.put("clientRouteId","0")
        route.put("plan","balanced")
        route.put("note","")
        route.put("length",""+segment.get("distance").toString().split(".")[0])
        route.put("time",""+segment.get("duration").toString().split(".")[0])
        route.put("busynance","403")
        route.put("quietness","44")
        route.put("signalledJunctions","0")
        route.put("signalledCrossings","0")
        route.put("west",""+bbox.get(0))
        route.put("south",""+bbox.get(3))
        route.put("east",""+bbox.get(1))
        route.put("north",""+bbox.get(2))
        route.put("name",firstStep.get("name") as String + " to" + lastStep.get("name") as String)
        route.put("walk","0")
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        val calendar = Calendar.getInstance()
        route.put("leaving",sdf.format(calendar.time))
        route.put("arriving",sdf.format(calendar.time))
        val coordinate = StringBuilder();
        for (index in 0 until coordinates.length()){
            val temp = coordinates.get(index) as JSONArray
            coordinate.append(temp.get(0))
            coordinate.append(",")
            coordinate.append(temp.get(1))
            coordinate.append(" ")
        }
        coordinate.trimEnd()
        coordinate.append(",")
        route.put("coordinate",coordinate)
        route.put("elevation","42,42,44,44,44,45,45,45")
        route.put("distances","5,47,20,7,37,24,38")
        route.put("grammesCO2saved","33")
        route.put("calories","5")
        route.put("edition","routing220904")
        route.put("type","route")
        returnedJson.put("route",route)

        //add segment
        val segments = JSONArray();
        for(index in 0 until stepArr.length()){
            val segment = JSONObject();
            val step = stepArr.get(index) as JSONObject
            val distance = step.get("distance")
            segment.put("name",step.get("name"))
            segment.put("legNumber","1")
            segment.put("distance",""+distance.toString().split(".")[0])
            segment.put("time",""+step.get("duration").toString().split(".")[0])
            segment.put("busynance","10")
            segment.put("quietness","60")
            segment.put("flow","0")
            segment.put("walk","0")
            segment.put("signalledJunctions","0")
            segment.put("signalledCrossings","0")
            segment.put("turn","")
            segment.put("startBearing","147")
            segment.put("color","#33aa33")
            val way_point = step.get("way_points") as JSONArray
            val from = way_point[0] as Int
            val to = way_point[1] as Int
            val coordinate = StringBuilder()
            for (i in from .. to){
                val temp = coordinates.get(i) as JSONArray
                coordinate.append(temp[0])
                coordinate.append(",")
                coordinate.append(temp[1])
                coordinate.append(" ")
            }
            segment.put("points",coordinate.toString().trim())
            segment.put("distances","0,5,47,20,7,37,24,38")
            segment.put("elevations","42,42,44,44,44,45,45,45")
            segment.put("provisionName","Minor road")
            segment.put("type","segment")
            segments.put(segment)
        }
        returnedJson.put("segments",segments)
        return returnedJson.toString();
    }

}

class ErrorFromServerException(errorMessage: String): Exception(errorMessage)
